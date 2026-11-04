"""Builds the exercise stick figures from a 3D body model, writes Figures.kt and a contact sheet.

Run from the repository root:
  python3 tools/gen_figures.py /tmp/figures.png core/src/main/kotlin/com/dmeltz/personalworkout/core/Figures.kt

Every exercise is posed once, in 3D, as a few keyframes. Each view (side, front, back or a raised
three-quarter view) is a projection of the same poses, so the views always agree. Limbs that touch
something (hands on the bar or floor, feet on the floor or a box) are placed by inverse kinematics on
fixed points, so contacts hold, and check() reports any contact the body can't reach.

World: x = the person's left (before any turn), y = up, z = the way they face. Floor at y = 0.
Lengths: torso 28, neck 8, upper arm 13, forearm 13, thigh 20, shin 20, foot 5, head radius 5.
"""
import math, sys
import numpy as np
from PIL import Image, ImageDraw

TORSO, NECK, UPPER, FORE, THIGH, SHIN, FOOT, HEAD_R = 28, 8, 13, 13, 20, 20, 5, 5
SH, HH = 7.0, 4.5  # half shoulder and hip width
X, Y, Z = np.array([1., 0, 0]), np.array([0., 1, 0]), np.array([0., 0, 1])


def unit(v):
    n = np.linalg.norm(v)
    return v / n if n > 1e-9 else v


def rot(v, axis, deg):
    """Rotates v about axis by deg (right hand rule)."""
    k = unit(axis); t = math.radians(deg)
    return v * math.cos(t) + np.cross(k, v) * math.sin(t) + k * np.dot(k, v) * (1 - math.cos(t))


# ───────────────────────────── limb specs ─────────────────────────────

def fk(flex=0, abd=0, bend=0, out=0):
    """Joint angles. flex: 0 hanging down, 90 forward, 180 overhead (legs: 90 = thigh forward).
    abd: out to the side. bend: elbow or knee bend in degrees (elbows bend forward, knees back).
    out: turns the bend direction outward, e.g. elbows flaring out."""
    return ("fk", flex, abd, bend, out)


def ik(target, pole, planted=True):
    """End point (hand or foot) at a world point; the elbow or knee bends toward the pole direction."""
    return ("ik", np.array(target, float), np.array(pole, float), planted)


def rel(joint, offset, pole):
    """End point at an offset (world axes) from another joint of the same pose, e.g. hands on hips."""
    return ("rel", joint, np.array(offset, float), np.array(pole, float))


# ───────────────────────────── a pose ─────────────────────────────

class Frame:
    """A built pose, kept as positions and directions so poses can be blended without stretching."""

    def __init__(self, pelvis, up, fwd, shrug, head_dir, face_dir, limbs, toe_dirs):
        self.pelvis, self.up, self.fwd, self.shrug = pelvis, up, fwd, shrug
        self.head_dir, self.face_dir = head_dir, face_dir
        self.limbs = limbs        # name -> (upper dir, lower dir)
        self.toe_dirs = toe_dirs  # "L"/"R" -> dir
        self.left = unit(np.cross(up, fwd))

    def roots(self):
        neck = self.pelvis + self.up * TORSO
        sh = self.up * self.shrug
        return {
            "neck": neck,
            "shL": neck + self.left * SH + sh, "shR": neck - self.left * SH + sh,
            "hipL": self.pelvis + self.left * HH, "hipR": self.pelvis - self.left * HH,
        }

    def joints(self):
        j = dict(self.roots())
        j["pelvis"] = self.pelvis
        j["head"] = j["neck"] + self.head_dir * NECK
        side = unit(np.cross(self.head_dir, self.face_dir))  # the head's left
        for s, sign in (("L", 1), ("R", -1)):
            j["eye" + s] = j["head"] + HEAD_R * 0.62 * unit(self.face_dir * 0.85 + side * 0.42 * sign + self.head_dir * 0.2)
        for limb, root, mid, end, l1, l2 in (("armL", "shL", "elL", "haL", UPPER, FORE), ("armR", "shR", "elR", "haR", UPPER, FORE),
                                            ("legL", "hipL", "knL", "ftL", THIGH, SHIN), ("legR", "hipR", "knR", "ftR", THIGH, SHIN)):
            u, l = self.limbs[limb]
            j[mid] = j[root] + u * l1
            j[end] = j[mid] + l * l2
        for s in "LR":
            j["to" + s] = j["ft" + s] + self.toe_dirs[s] * FOOT
        return j


def two_bone(root, target, l1, l2, pole):
    """Middle joint direction and lower direction reaching from root toward target, bending toward pole.
    Returns (upper dir, lower dir, miss): miss is how far the end point falls short of the target."""
    d_vec = target - root
    d = np.linalg.norm(d_vec)
    t = unit(d_vec)
    reach = min(max(d, abs(l1 - l2) + 1e-3), l1 + l2 - 1e-3)
    cos_a = (l1 * l1 + reach * reach - l2 * l2) / (2 * l1 * reach)
    a = math.acos(max(-1, min(1, cos_a)))
    p = pole - np.dot(pole, t) * t
    if np.linalg.norm(p) < 1e-6:
        p = np.cross(t, X) if abs(t[0]) < 0.9 else np.cross(t, Y)
    p = unit(p)
    mid = root + l1 * (math.cos(a) * t + math.sin(a) * p)
    end = root + t * reach
    return unit(mid - root), unit(end - mid), abs(d - reach)


def build(spec, misses=None, tag=""):
    """spec keys: hip (pelvis point), yaw/pitch/roll (degrees; pitch tips the body forward), shrug,
    head (chin down +), turn (head turned to its left +), armL/armR/legL/legR (fk/ik/rel), anchor
    (joint name, world point, axes 'xyz' to fix), feet ("flat" toward the facing direction, "shin" =
    at right angles to the shin, or a number = degrees the heel is raised), toes (world direction)."""
    up, fwd = Y.copy(), Z.copy()
    p = spec.get("pitch", 0)
    up, fwd = rot(up, X, p), rot(fwd, X, p)
    r = spec.get("roll", 0)
    up = rot(up, fwd, -r)
    yaw = spec.get("yaw", 0)
    up, fwd = rot(up, Y, yaw), rot(fwd, Y, yaw)
    pelvis = np.array(spec.get("hip", (0, 40, 0)), float)
    left = unit(np.cross(up, fwd))
    head_dir = rot(up, left, spec.get("head", 0))
    face_dir = rot(fwd, left, spec.get("head", 0))
    turn = spec.get("turn", 0)
    face_dir = rot(face_dir, head_dir, turn)
    frame = Frame(pelvis, up, fwd, spec.get("shrug", 0), head_dir, face_dir, {}, {})

    def fk_dirs(limb, s):
        _, flex, abd, bend, out = s
        arm = limb.startswith("arm")
        sign = 1 if limb.endswith("L") else -1
        down = -up
        d = rot(down, left, -flex)
        bdir = rot(fwd, left, -flex) if arm else -rot(fwd, left, -flex)
        d = rot(d, fwd, sign * abd); bdir = rot(bdir, fwd, sign * abd)
        bdir = rot(bdir, d, -sign * out) if arm else bdir
        lower = unit(math.cos(math.radians(bend)) * d + math.sin(math.radians(bend)) * bdir)
        return unit(d), lower

    # Joint-angle limbs first, then move the body to its anchor, then reach for fixed points.
    for limb in ("armL", "armR", "legL", "legR"):
        s = spec.get(limb, fk())
        if s[0] == "fk":
            frame.limbs[limb] = fk_dirs(limb, s)
        else:
            frame.limbs[limb] = (-up, -up)  # placeholder until the anchor is known
    set_toes(frame, spec)
    if "anchor" in spec:
        name, point, axes = spec["anchor"]
        j = frame.joints()
        delta = np.array(point, float) - j[name]
        for i, a in enumerate("xyz"):
            if a not in axes: delta[i] = 0
        frame.pelvis = frame.pelvis + delta
    roots = frame.roots()
    roots["pelvis"] = frame.pelvis
    for limb, root, l1, l2 in (("armL", "shL", UPPER, FORE), ("armR", "shR", UPPER, FORE),
                               ("legL", "hipL", THIGH, SHIN), ("legR", "hipR", THIGH, SHIN)):
        s = spec.get(limb, fk())
        if s[0] == "ik":
            u, l, miss = two_bone(roots[root], s[1], l1, l2, s[2])
            frame.limbs[limb] = (u, l)
            if misses is not None and miss > 0.6 and s[3]:
                misses.append(f"{tag} {limb} misses its point by {miss:.1f}")
        elif s[0] == "rel":
            j = frame.joints()
            u, l, miss = two_bone(roots[root], j[s[1]] + s[2], l1, l2, s[3])
            frame.limbs[limb] = (u, l)
            if misses is not None and miss > 0.6:
                misses.append(f"{tag} {limb} misses {s[1]} by {miss:.1f}")
    set_toes(frame, spec)
    return frame


def set_toes(frame, spec):
    feet = spec.get("feet", "flat")
    for s in "LR":
        mode = spec.get("feet" + s, feet)
        shin = frame.limbs["leg" + s][1]
        if "toes" in spec:
            frame.toe_dirs[s] = unit(np.array(spec["toes"], float))
        elif mode == "line":
            frame.toe_dirs[s] = shin
        elif mode == "shin":
            # At right angles to the shin, on the front side of the leg.
            knee_fwd = frame.fwd - np.dot(frame.fwd, shin) * shin
            frame.toe_dirs[s] = unit(knee_fwd) if np.linalg.norm(knee_fwd) > 1e-3 else unit(np.cross(frame.left, shin))
        else:
            raise_deg = 0 if mode == "flat" else mode
            h = frame.fwd - np.dot(frame.fwd, Y) * Y
            h = unit(h) if np.linalg.norm(h) > 1e-3 else unit(frame.up - np.dot(frame.up, Y) * Y)
            h = rot(h, Y, 8 if s == "L" else -8)  # toes turned out a little
            frame.toe_dirs[s] = unit(h * math.cos(math.radians(raise_deg)) - Y * math.sin(math.radians(raise_deg)))


def blend(a, b, t, sa, sb, misses=None):
    """A pose between two built poses. Directions are blended and renormalized, so lengths hold;
    limbs planted on the same point in both poses are solved again so they stay put."""
    def mix(u, v): return unit(u * (1 - t) + v * t) if np.linalg.norm(u * (1 - t) + v * t) > 1e-6 else u
    up = mix(a.up, b.up)
    fwd = mix(a.fwd, b.fwd); fwd = unit(fwd - np.dot(fwd, up) * up)
    f = Frame(a.pelvis * (1 - t) + b.pelvis * t, up, fwd, a.shrug * (1 - t) + b.shrug * t,
              mix(a.head_dir, b.head_dir), mix(a.face_dir, b.face_dir),
              {k: (mix(a.limbs[k][0], b.limbs[k][0]), mix(a.limbs[k][1], b.limbs[k][1])) for k in a.limbs},
              {k: mix(a.toe_dirs[k], b.toe_dirs[k]) for k in a.toe_dirs})
    roots = f.roots()
    for limb, root, l1, l2 in (("armL", "shL", UPPER, FORE), ("armR", "shR", UPPER, FORE),
                               ("legL", "hipL", THIGH, SHIN), ("legR", "hipR", THIGH, SHIN)):
        x, y = sa.get(limb, fk()), sb.get(limb, fk())
        if x[0] == "ik" and y[0] == "ik" and np.allclose(x[1], y[1]):
            u, l, _ = two_bone(roots[root], x[1], l1, l2, x[2] * (1 - t) + y[2] * t)
            f.limbs[limb] = (u, l)
    return f


# ───────────────────────────── views ─────────────────────────────

VIEWS = {
    # name: (azimuth from the person's right side toward their front, elevation, label)
    "side": (0, 0, "Side"),
    "front": (90, 0, "Front"),
    "back": (-90, 0, "Back"),
    "threeq": (40, 22, "3/4 view"),
    "threeq_low": (45, 8, "3/4 view"),
    "above": (65, 32, "3/4 view"),
    "high": (12, 38, "From above"),
    "back3q": (-50, 10, "3/4 view"),
}


def camera(view):
    az, el, _ = VIEWS[view]
    a, e = math.radians(az), math.radians(el)
    pos = np.array([-math.cos(a) * math.cos(e), math.sin(e), math.sin(a) * math.cos(e)])
    d = -pos                                  # looking direction
    right = unit(np.cross(d, Y))
    upv = unit(np.cross(right, d))
    return right, upv, d


def project(p, cam):
    right, upv, d = cam
    return np.array([np.dot(p, right), np.dot(p, upv), np.dot(p, d)])


# ───────────────────────────── props ─────────────────────────────

def bar(y, z=0.0, half=30): return ("bar", np.array([-half, y, z]), np.array([half, y, z]))
def box(x0, x1, y0, y1, z0, z1): return ("box", (x0, x1, y0, y1, z0, z1))
def bag(x, z, r, y0, y1): return ("bag", x, z, r, y0, y1)
def door(z, half=14, height=96, handle=(0, 45)): return ("door", z, half, height, handle)
def tether(joint, point): return ("tether", joint, np.array(point, float))
def mat(): return ("mat", None)


# ───────────────────────────── the figures ─────────────────────────────

FIG = {}


def figure(name, keys, views, props=(), loop=False, steps=4, ms=None, hanging=False):
    FIG[name] = dict(keys=keys, views=views, props=list(props), loop=loop, steps=steps, ms=ms, hanging=hanging)


STAND = dict(hip=(0, 40, 0), armL=fk(5, 6, 12), armR=fk(5, 6, 12), anchor=("ftL", (0, 0, 0), "y"))

def stand(**kw):
    s = dict(STAND); s.update(kw); return s


# Warm-up: marching, opposite arm and leg.
figure("WU_MARCH", [
    stand(legL=fk(80, 0, 90), legR=fk(0, 0, 5), armR=fk(50, 6, 70), armL=fk(-35, 6, 20), anchor=("ftR", (0, 0, 0), "y")),
    stand(legL=fk(5, 0, 10), legR=fk(5, 0, 10), armL=fk(5, 6, 30), armR=fk(5, 6, 30), anchor=("ftR", (0, 0, 0), "y")),
    stand(legR=fk(80, 0, 90), legL=fk(0, 0, 5), armL=fk(50, 6, 70), armR=fk(-35, 6, 20), anchor=("ftL", (0, 0, 0), "y")),
    stand(legL=fk(5, 0, 10), legR=fk(5, 0, 10), armL=fk(5, 6, 30), armR=fk(5, 6, 30), anchor=("ftL", (0, 0, 0), "y")),
], ["side", "front"], loop=True, ms=1600)

# Warm-up: arm circles, arms out to the sides.
def circle_arms(phase):
    a = math.radians(phase)
    return stand(armL=fk(25 * math.cos(a), 85 + 25 * math.sin(a), 0), armR=fk(25 * math.cos(a), 85 + 25 * math.sin(a), 0))
figure("WU_ARMS", [circle_arms(p) for p in range(0, 360, 45)], ["front", "threeq_low"], loop=True, steps=2, ms=1400)

# Warm-up: hip circles, hands on hips, feet planted.
def hip_circle(phase):
    a = math.radians(phase)
    dx, dz = 4 * math.cos(a), 4 * math.sin(a)
    return dict(hip=(dx, 39, dz), roll=-6 * math.cos(a), pitch=-6 * math.sin(a),
                legL=ik((8, 0, 0), (0.3, 0, 1)), legR=ik((-8, 0, 0), (-0.3, 0, 1)),
                armL=rel("hipL", (3, 3, 0), (1, 0, -1)), armR=rel("hipR", (-3, 3, 0), (-1, 0, -1)))
figure("WU_HIPS", [hip_circle(p) for p in range(0, 360, 90)], ["front", "side"], loop=True, steps=3, ms=2000)

# Warm-up: good mornings, hands behind the head, flat back.
figure("WU_HINGE", [
    dict(hip=(0, 39.8, 0), legL=ik((5, 0, 0), (0, 0, 1)), legR=ik((-5, 0, 0), (0, 0, 1)),
         armL=rel("head", (3, -1, -5), (1, 0.2, 0.3)), armR=rel("head", (-3, -1, -5), (-1, 0.2, 0.3))),
    dict(hip=(0, 37, -9), pitch=72, head=-20, legL=ik((5, 0, 0), (0, 0, 1)), legR=ik((-5, 0, 0), (0, 0, 1)),
         armL=rel("head", (3, 3, -3), (1, 0.6, 0)), armR=rel("head", (-3, 3, -3), (-1, 0.6, 0))),
], ["side", "front"])

# Warm-up: shoulder rolls and slow neck turns.
figure("WU_SHOULDERS", [
    stand(shrug=4, turn=0),
    stand(shrug=0, turn=55),
    stand(shrug=-2, turn=0),
    stand(shrug=0, turn=-55),
], ["front", "side"], loop=True, steps=3, ms=3200)

# ── Push ──
def plank_line(shoulder_y, foot_y=3.0):
    """Pitch for a straight body from the toes up to a shoulder height."""
    return 90 - math.degrees(math.asin((shoulder_y - foot_y) / (TORSO + THIGH + SHIN)))

def prone(shoulder_y, hands_x, hands_dz, pole_out, pole_back, extra=None, hands=None, hand_y=0.0, feet_y=0.0, shift=0.0):
    """A straight body from the toes (on the floor, or raised to feet_y) to the shoulders, hands on
    the floor or on a surface at hand_y. shift moves the body sideways (archer push-ups)."""
    p = plank_line(shoulder_y, feet_y + 3)
    s = dict(pitch=p, hip=(shift, 0, 0), legL=fk(0, 2, 0), legR=fk(0, 2, 0), feet="shin", anchor=("toL", (0, feet_y, -40), "yz"))
    if extra: s.update(extra)
    f = build(s)
    j = f.joints()
    if hands is None:
        hands = [(hands_x, hand_y, j["shL"][2] + hands_dz), (-hands_x, hand_y, j["shR"][2] + hands_dz)]
    s["armL"] = ik(hands[0], (pole_out, 0.5, -pole_back))
    s["armR"] = ik(hands[1], (-pole_out, 0.5, -pole_back))
    return s, hands

bottom, hands = prone(8, 9.5, -3, 0.8, 1)
top, _ = prone(24, 9.5, 0, 0.8, 1, hands=hands)
figure("PUSHUP", [top, bottom], ["side", "high"], props=[mat()])

bottom, hands = prone(9, 3.5, -5, 0.1, 1)
top, _ = prone(24, 3.5, 0, 0.1, 1, hands=hands)
figure("CLOSE_PUSHUP", [top, bottom], ["side", "high"], props=[mat()])

# Pike push-up: hips high, head goes down between the hands.
def pike(torso_pitch, hip_flex, bend_pole):
    """Straight legs, feet fixed; the torso tips down and the elbows bend toward the feet."""
    return dict(pitch=torso_pitch, head=-25, legL=fk(hip_flex, 0, 0), legR=fk(hip_flex, 0, 0), toes=(0, -0.6, 1),
                anchor=("ftL", (5, 3.6, -36), "yz"),
                armL=ik((9, 0, 6), (0.5, 0.4, bend_pole)), armR=ik((-9, 0, 6), (-0.5, 0.4, bend_pole)))
figure("PIKE", [
    pike(129, 112, 0),
    pike(139, 92, -1),
], ["side", "high"], props=[mat()])

# ── Pull (bar along the shoulders, at height 100) ──
BAR = 100
def hang(grip, hip_dy=0.0, shrug=0.0, legs=fk(8, 0, 25), z=0.0, pitch=0, pole=(1, -0.3, -0.3)):
    hip_y = BAR - TORSO - 25.6 + hip_dy
    return dict(hip=(0, hip_y, z), pitch=pitch, shrug=shrug, legL=legs, legR=legs, feet="shin",
                armL=ik((grip, BAR, 0), pole), armR=ik((-grip, BAR, 0), (-pole[0], pole[1], pole[2])))

figure("HANG", [hang(9.5, 0, 0), hang(9.5, 2.5, -2.5)], ["front", "side"], props=[bar(BAR)], hanging=True)

def pull_top(grip, pole, legs=fk(15, 0, 35)):
    return dict(hip=(0, BAR - TORSO - 7, -0.5), pitch=-7, head=-10, legL=legs, legR=legs, feet="shin",
                armL=ik((grip, BAR, 0), pole), armR=ik((-grip, BAR, 0), (-pole[0], pole[1], pole[2])))
figure("PULLUP", [hang(13, pole=(1, -0.2, -0.2)), pull_top(13, (1, -0.7, -0.3))],
       ["back", "side"], props=[bar(BAR)], hanging=True)
figure("CHINUP", [hang(6.5, pole=(0.2, -0.3, 1)), pull_top(6.5, (0.25, -0.6, 1))],
       ["front", "side"], props=[bar(BAR)], hanging=True)

figure("KNEERAISE", [
    hang(9.5, legs=fk(5, 0, 8)),
    dict(hang(9.5, hip_dy=1.5, pitch=-10), legL=fk(100, 0, 95), legR=fk(100, 0, 95)),
], ["side", "front"], props=[bar(BAR)], hanging=True)

# Door towel row: towel around the handle of a closed door, lean back, pull the chest to the hands.
HANDLE = np.array([0, 45, 34.0])
def row(pitch, hands_dist, elbows):
    s = dict(pitch=pitch, head=-pitch * 0.3, legL=fk(0, 0, 0), legR=fk(0, 0, 0), anchor=("ftL", (5, 0, 0), "xyz"))
    f = build(s); j = f.joints()
    for side, sh in (("L", j["shL"]), ("R", j["shR"])):
        toward = unit(HANDLE + np.array([2.5 if side == "L" else -2.5, 0, 0]) - sh)
        s["arm" + side] = ik(sh + toward * hands_dist, (elbows * (1 if side == "L" else -1), -0.6, -1), planted=False)
    return s
figure("ROW", [row(-32, 26, 0.3), row(-22, 9, 0.8)],
       ["side", "back"], props=[door(36), tether("haL", HANDLE), tether("haR", HANDLE)])

# ── Legs ──
FEET = [(6, 0, 0), (-6, 0, 0)]
def squat(hip, pitch, arms):
    return dict(hip=hip, pitch=pitch, head=-pitch * 0.4, armL=arms, armR=arms,
                legL=ik(FEET[0], (0.35, 0, 1)), legR=ik(FEET[1], (-0.35, 0, 1)))
figure("SQUAT", [
    squat((0, 39.7, 0), 0, fk(5, 6, 10)),
    squat((0, 19, -13), 38, fk(118, 6, 5)),
], ["side", "front"])

def split(hip, back_raise, arms=fk(5, 8, 10)):
    return dict(hip=hip, armL=arms, armR=arms, feetR="flat", feetL=back_raise,
                legR=ik((-5, 0, 14), (0, 0, 1)), legL=ik((5, 3.8, -16), (0, -0.2, 1)))
figure("SPLIT", [split((0, 37.5, -1), 50), split((0, 21, -2), 60)], ["side", "front"])

def bridge_pose(lift):
    """On the back, shoulders on the floor; lift = degrees the torso rises from the shoulders."""
    s = dict(pitch=-90 - lift, yaw=180, head=lift, anchor=("neck", (0, 5, 30), "yz"), toes=(0, 0, -1))
    f = build(s); j = f.joints()
    for side in "LR":
        x = j["sh" + side][0]
        s["leg" + side] = ik((np.sign(x) * 6, 0, -12.8), (0, 1, 0.2))
        s["arm" + side] = ik((np.sign(x) * 12, 0, 12), (np.sign(x), 0.3, 0))
    return s
figure("BRIDGE", [bridge_pose(0), bridge_pose(18)], ["side", "high"], props=[mat()])

figure("CALF", [stand(), stand(feet=55, anchor=("toL", (0, 0, 0), "y"))], ["side", "threeq_low"])

BOX = box(-14, 14, 0, 16, 9, 33)
figure("STEPUP", [
    dict(hip=(0, 38.5, 2), pitch=10, legL=ik((5, 16, 18), (0, 0, 1)), legR=ik((-5, 0, 0), (0, 0, 1)), armL=fk(-10, 6, 20), armR=fk(30, 6, 30)),
    dict(hip=(0, 47, 10), pitch=18, legL=ik((5, 16, 18), (0, 0, 1)), legR=ik((-5, 15, 4), (0, -0.3, 1), planted=False), armL=fk(15, 6, 20), armR=fk(5, 6, 30)),
    dict(hip=(0, 55.8, 18), legL=ik((5, 16, 18), (0, 0, 1)), legR=ik((-5, 16, 18), (0, 0, 1)), armL=fk(5, 6, 12), armR=fk(5, 6, 12)),
], ["side", "front"], props=[BOX])

# ── Core ──
def forearm_plank(extra=None):
    p = plank_line(13.5)
    s = dict(pitch=p, legL=fk(0, 2, 0), legR=fk(0, 2, 0), feet="shin", anchor=("toL", (0, 0, -40), "yz"), head=10)
    f = build(s); j = f.joints()
    s["armL"] = ik((4, 0, j["shL"][2] + 13), (0.1, -1, 0))
    s["armR"] = ik((-4, 0, j["shR"][2] + 13), (-0.1, -1, 0))
    if extra: s.update(extra)
    return s
figure("PLANK", [forearm_plank(), forearm_plank(dict(legR=fk(-8, 2, 0)))], ["side", "high"], props=[mat()])

def side_plank(top_arm, top_leg=fk(0, 0, 0), lift=0.0):
    # Lying on the right side, facing the front camera; the right forearm is on the floor.
    s = dict(roll=-90 + 18 + lift, yaw=0, hip=(0, 0, 0), legL=top_leg, legR=fk(0, 0, 0), feet="shin",
             anchor=("ftR", (-38, 1.5, 0), "xy"), armL=top_arm)
    f = build(s); j = f.joints()
    s["armR"] = ik((j["shR"][0] + 1, 0, j["shR"][2] + 12), (0, -1, 0))
    return s
figure("SIDEPLANK", [side_plank(fk(0, 8, 0)), side_plank(fk(0, 92, 0))], ["front", "above"],
       props=[mat()])

def supine(arm_l, arm_r, leg_l, leg_r):
    return dict(pitch=-90, yaw=180, hip=(0, 5, 0), armL=arm_l, armR=arm_r, legL=leg_l, legR=leg_r, feet="shin")
UP_ARM, OVER, TABLE, LONG = fk(90, 4, 0), fk(165, 6, 0), fk(90, 4, 90), fk(12, 4, 0)
figure("DEADBUG", [
    supine(UP_ARM, UP_ARM, TABLE, TABLE),
    supine(OVER, UP_ARM, TABLE, LONG),
    supine(UP_ARM, UP_ARM, TABLE, TABLE),
    supine(UP_ARM, OVER, LONG, TABLE),
], ["side", "high"], props=[mat()], loop=True, ms=3600)

def quad(reach_arm=None, reach_leg=None):
    p = 90 - math.degrees(math.asin((26 - 20) / TORSO))
    s = dict(pitch=p, hip=(0, 20, 0), feet="line")
    f = build(s); j = f.joints()
    for side, sign in (("L", 1), ("R", -1)):
        s["arm" + side] = ik((j["sh" + side][0], 0, j["sh" + side][2]), (0, 0, -1))
        s["leg" + side] = ik((j["hip" + side][0], 1.5, j["hip" + side][2] - 19.9), (0, -1, 0))
    if reach_arm: s[reach_arm] = fk(175, 4, 0)
    if reach_leg: s[reach_leg] = fk(2, 2, 0)
    return s
figure("BIRDDOG", [quad(), quad("armL", "legR"), quad(), quad("armR", "legL")],
       ["side", "high"], props=[mat()], loop=True, ms=4000)

# ── Conditioning ──
BAG_Z = 30
def boxing(jab=False, cross=False):
    s = dict(hip=(0, 38, 0), yaw=-18, head=8, feetL="flat", feetR=30,
             legL=ik((5, 0, 9), (0.2, 0, 1)), legR=ik((-5, 2, -9), (0, 0, 1)))
    guard_l, guard_r = (3, 67, 8), (-4, 66, 5)
    s["armL"] = ik((2, 66, BAG_Z - 9.5) if jab else guard_l, (1, -1, -0.2), planted=False)
    s["armR"] = ik((0, 66, BAG_Z - 9.5) if cross else guard_r, (-1, -1, -0.2), planted=False)
    if cross:
        s["yaw"] = 12; s["hip"] = (0, 38, 1.5)
    return s
figure("BAG", [boxing(), boxing(jab=True), boxing(), boxing(cross=True)],
       ["side", "back3q"], props=[bag(0, BAG_Z, 9, 32, 92)], loop=True, ms=1800)

def bear(step=0):
    """Hands under shoulders, knees under hips and off the floor. step ±1 moves one hand and the
    opposite foot forward."""
    p = 90 - math.degrees(math.asin((26 - 22) / TORSO))
    s = dict(pitch=p, hip=(0, 22, 0), head=-15, feet="shin")
    f = build(s); j = f.joints()
    for side, sign in (("L", 1), ("R", -1)):
        hand_dz = 6 * step * sign
        foot_dz = -6 * step * sign
        s["arm" + side] = ik((j["sh" + side][0] + 1.5 * sign, 0, j["sh" + side][2] + hand_dz), (0, 0, -1), planted=False)
        s["leg" + side] = ik((j["hip" + side][0], 5.5, j["hip" + side][2] - 15 + foot_dz), (0, -0.3, 1), planted=False)
    return s
figure("BEAR", [bear(0), bear(1), bear(0), bear(-1)], ["side", "high"], props=[mat()], loop=True, ms=2400)


# ───────────────────────────── variations ─────────────────────────────
# Every variation in exercises.txt names a figure. Slow variations share their base figure's poses,
# played slower.

def alias(name, base, ms):
    FIG[name] = dict(FIG[base], ms=ms)

alias("PUSHUP_SLOW", "PUSHUP", 3600)
alias("PIKE_SLOW", "PIKE", 3600)
alias("PULLUP_SLOW", "PULLUP", 3600)
alias("CHINUP_SLOW", "CHINUP", 3600)
alias("ROW_SLOW", "ROW", 3200)
alias("SQUAT_PAUSE", "SQUAT", 3600)
alias("DEADBUG_SLOW", "DEADBUG", 6000)

# Push-ups with hands on a table, feet raised, archer, diamond.
TABLE_H = 38
def push_pair(top_y, bottom_y, hands_x, dz, pole_out, hand_y=0.0, feet_y=0.0, bottom_dz=-3, from_top=False):
    if from_top:
        # Hands placed under the shoulders at the top (an incline would overreach otherwise).
        top, hands = prone(top_y, hands_x, dz, pole_out, 1, hand_y=hand_y, feet_y=feet_y)
        bottom, _ = prone(bottom_y, hands_x, bottom_dz, pole_out, 1, hands=hands, hand_y=hand_y, feet_y=feet_y)
        return top, bottom, hands
    bottom, hands = prone(bottom_y, hands_x, bottom_dz, pole_out, 1, hand_y=hand_y, feet_y=feet_y)
    top, _ = prone(top_y, hands_x, dz, pole_out, 1, hands=hands, hand_y=hand_y, feet_y=feet_y)
    return top, bottom, hands

top, bottom, hands = push_pair(TABLE_H + 23, TABLE_H + 8, 9.5, 2, 0.8, hand_y=TABLE_H, from_top=True)
hz = hands[0][2]
figure("PUSHUP_INCLINE", [top, bottom], ["side", "threeq"], props=[box(-22, 22, 0, TABLE_H, hz - 5, hz + 22)])
top, bottom, hands = push_pair(TABLE_H + 23, TABLE_H + 9, 3.5, 2, 0.1, hand_y=TABLE_H, from_top=True)
hz = hands[0][2]
figure("CLOSE_INCLINE", [top, bottom], ["side", "threeq"], props=[box(-22, 22, 0, TABLE_H, hz - 5, hz + 22)])

CHAIR_H = 20
top, bottom, _ = push_pair(24, 8, 9.5, 0, 0.8, feet_y=CHAIR_H)
figure("PUSHUP_FEET_UP", [top, bottom], ["side", "high"], props=[box(-14, 14, 0, CHAIR_H, -50, -30)])

top, bottom, _ = push_pair(24, 9, 1.8, -2, 0.6, bottom_dz=-6)
figure("DIAMOND_PUSHUP", [top, bottom], ["side", "high"], props=[mat()])

def archer(side):
    """side 0: top; ±1: lowered toward the left or right hand."""
    hands = [(20, 0, 0), (-20, 0, 0)]
    if side == 0:
        s, _ = prone(21, 0, 0, 0.8, 1, shift=0)
    else:
        s, _ = prone(10, 0, 0, 0.8, 1, shift=9 * side)
    j = build(s).joints()
    hz = 0.0
    return s
# Hands fixed wide, under the shoulders' line.
_s = archer(0); _j = build(_s).joints(); _hz = _j["shL"][2] - 1
def archer_pose(side):
    s = archer(side)
    for limb, x, sgn in (("armL", 20, 1), ("armR", -20, -1)):
        bent = (side == 1 and sgn == 1) or (side == -1 and sgn == -1)
        s[limb] = ik((x, 0, _hz), (sgn * 0.2, 0.6, -1) if bent else (sgn * 0.2, 1, 0.2))
    return s
figure("ARCHER_PUSHUP", [archer_pose(0), archer_pose(1), archer_pose(0), archer_pose(-1)], ["front", "high"],
       props=[mat()], loop=True, ms=3600)

# Pike push-up with feet on a chair: hips stacked over the shoulders.
def pike_up(torso_pitch, hip_flex, bend_pole):
    return dict(pitch=torso_pitch, head=-25, legL=fk(hip_flex, 0, 0), legR=fk(hip_flex, 0, 0), toes=(0, -0.6, 1),
                anchor=("ftL", (5, CHAIR_H + 3.6, -30), "yz"),
                armL=ik((9, 0, 8), (0.5, 0.4, bend_pole)), armR=ik((-9, 0, 8), (-0.5, 0.4, bend_pole)))
figure("PIKE_FEET_UP", [pike_up(156, 94, 0), pike_up(158, 88, -1)], ["side", "high"],
       props=[box(-14, 14, 0, CHAIR_H, -44, -24)])

# ── Hanging ──
figure("HANG_DEAD", [hang(9.5, 0, 0), hang(9.5, 0.4, 0)], ["front", "side"], props=[bar(BAR)], hanging=True, ms=2400)
FIG["HANG_ACTIVE"] = dict(FIG["HANG"])
figure("HANG_FLEXED", [pull_top(9.5, (1, -0.7, -0.3)), dict(pull_top(9.5, (1, -0.7, -0.3)), hip=(0, BAR - TORSO - 7.6, -0.5))],
       ["front", "side"], props=[bar(BAR)], hanging=True, ms=2400)

def supported(hip_dy):
    s = hang(9.5, hip_dy)
    s["legL"] = ik((5, CHAIR_H, 14), (0, 0.3, 1)); s["legR"] = ik((-5, CHAIR_H, 14), (0, 0.3, 1))
    s["feet"] = "flat"
    return s
figure("HANG_SUPPORTED", [supported(0), supported(0.8)], ["side", "front"], hanging=True, ms=2400,
       props=[bar(BAR), box(-14, 14, 0, CHAIR_H, 6, 26)])
# The figure hangs from the bar but stands on a chair, so draw the floor too.
FIG["HANG_SUPPORTED"]["hanging"] = False

def negative(grip, pole_top, pole_hang):
    top = pull_top(grip, pole_top)
    j = build(top).joints()
    chair_y = min(j["ftL"][1], j["ftR"][1]) - 1.5
    bottom = hang(grip, pole=pole_hang, legs=fk(20, 0, 115))  # knees bent to clear the chair
    return [top, bottom], box(-14, 14, 0, chair_y, j["ftL"][2] - 9, j["ftL"][2] + 9)
keys, chair = negative(13, (1, -0.7, -0.3), (1, -0.2, -0.2))
figure("PULLUP_NEGATIVE", keys, ["back", "side"], props=[bar(BAR), chair], ms=4000)
keys, chair = negative(6.5, (0.25, -0.6, 1), (0.2, -0.3, 1))
figure("CHINUP_NEGATIVE", keys, ["front", "side"], props=[bar(BAR), chair], ms=4000)

def hang_legs(legs_top, pitch=-10, hip_dy=1.5):
    return [hang(9.5, legs=fk(5, 0, 8)), dict(hang(9.5, hip_dy=hip_dy, pitch=pitch), legL=legs_top, legR=legs_top)]
figure("KNEE_TO_CHEST", hang_legs(fk(135, 0, 120), pitch=-22, hip_dy=4), ["side", "front"], props=[bar(BAR)], hanging=True)
figure("LEG_RAISE", hang_legs(fk(92, 0, 0), pitch=-14), ["side", "front"], props=[bar(BAR)], hanging=True)

def lying_tuck(tucked):
    legs = fk(125, 4, 125) if tucked else fk(30, 4, 30)
    return dict(pitch=-90 + (12 if tucked else 0), yaw=180, hip=(0, 5 + (3 if tucked else 0), 0),
                armL=fk(0, 12, 0), armR=fk(0, 12, 0), legL=legs, legR=legs, feet="shin")
figure("LYING_TUCK", [lying_tuck(False), lying_tuck(True)], ["side", "high"], props=[mat()])

# ── Rows ──
figure("ROW_UPRIGHT", [row(-18, 26, 0.3), row(-10, 9, 0.8)],
       ["side", "back"], props=[door(36), tether("haL", HANDLE), tether("haR", HANDLE)])
def one_arm_row(pitch, dist):
    s = row(pitch, dist, 0.6)
    s["armL"] = ik(build(s).joints()["shL"] + unit(HANDLE - build(s).joints()["shL"]) * dist, (0.3, -0.6, -1), planted=False)
    s["armR"] = rel("neck", (-2, -7, 4), (-1, -0.5, 0))
    s["yaw"] = 8
    return s
figure("ROW_ONE_ARM", [one_arm_row(-26, 26), one_arm_row(-14, 9)],
       ["side", "back"], props=[door(36), tether("haL", HANDLE)])

# ── Squats ──
figure("CHAIR_SQUAT", [squat((0, 39.7, 0), 0, fk(5, 6, 10)), squat((0, 22.5, -12), 32, fk(110, 6, 5))],
       ["side", "front"], props=[box(-14, 14, 0, CHAIR_H, -30, -10)])

def cossack(shift):
    hip = (0, 31, 0) if shift == 0 else (12 * shift, 13, -6)
    s = dict(hip=hip, pitch=0 if shift == 0 else 30, head=-10 if shift else 0,
             legL=ik((30, 0, 0), (0.4, 0, 1)), legR=ik((-30, 0, 0), (-0.4, 0, 1)),
             armL=fk(70 if shift else 10, 8, 10), armR=fk(70 if shift else 10, 8, 10))
    # The straight leg's toes point up.
    if shift == 1: s["feetR"] = -60
    if shift == -1: s["feetL"] = -60
    return s
figure("COSSACK", [cossack(0), cossack(1), cossack(0), cossack(-1)], ["front", "threeq"], loop=True, ms=4800)

def pistol(down):
    if not down:
        return dict(hip=(0, 39.6, 0), legL=ik((5, 0, 0), (0, 0, 1)), legR=fk(25, 0, 0), armL=fk(80, 8, 0), armR=fk(80, 8, 0))
    return dict(hip=(0, 19, -13), pitch=38, head=-15, legL=ik((5, 0, 0), (0, 0, 1)), legR=fk(110, 0, 0),
                armL=fk(115, 8, 0), armR=fk(115, 8, 0))
figure("PISTOL", [pistol(False), pistol(True)], ["side", "front"], props=[box(-14, 14, 0, 15, -32, -12)])

def split_supported(hip, back_raise):
    s = split(hip, back_raise)
    s["armR"] = ik((-17, 34, -1), (-1, -0.5, -0.3), planted=False)
    return s
figure("SPLIT_SUPPORTED", [split_supported((0, 37.5, -1), 50), split_supported((0, 21, -2), 60)], ["side", "front"],
       props=[box(-24, -14, 0, 33, -6, 4)])

def bulgarian(hip_y):
    return dict(hip=(0, hip_y, -3), armL=fk(5, 8, 10), armR=fk(5, 8, 10), feetR="flat", feetL="line",
                legR=ik((-5, 0, 12), (0, 0, 1)), legL=ik((5, CHAIR_H + 1.5, -25), (0, -0.4, 1)))
figure("BULGARIAN", [bulgarian(37), bulgarian(22)], ["side", "front"], props=[box(-12, 14, 0, CHAIR_H, -36, -20)])
alias("BULGARIAN_SLOW", "BULGARIAN", 3600)

# ── Bridges ──
def bridge_one_leg(lift, neck=(0, 5, 30)):
    s = bridge_pose(lift)
    s["anchor"] = ("neck", neck, "yz")
    s["legL"] = fk(88, 2, 0)
    return s
figure("BRIDGE_ONE_LEG", [bridge_one_leg(0), bridge_one_leg(18)], ["side", "high"], props=[mat()])

COUCH_H = 18
def hip_thrust(lift):
    s = dict(pitch=-90 - lift, yaw=180, head=lift, anchor=("neck", (0, COUCH_H + 3, 30), "yz"), toes=(0, 0, -1))
    j = build(s).joints()
    xr = j["shR"][0]
    s["legR"] = ik((np.sign(xr) * 6, 0, -16), (0, 1, 0.2))
    s["legL"] = fk(85, 2, 95)
    s["armL"] = fk(160, 40, 60); s["armR"] = fk(160, 40, 60)
    return s
figure("HIP_THRUST", [hip_thrust(-28), hip_thrust(0)], ["side", "threeq"], props=[box(-18, 18, 0, COUCH_H, 26, 46)])

# ── Calves ──
STEP_H = 8
def calf_step(raise_deg, one_leg=False):
    s = stand(feet=raise_deg, anchor=("toL", (0, STEP_H, 0), "y"))
    if one_leg:
        s["legR"] = fk(5, 0, 70)
        s["armR"] = fk(30, 30, 40)
    return s
figure("CALF_STEP", [calf_step(-28), calf_step(55)], ["side", "threeq_low"], props=[box(-14, 14, 0, STEP_H, 2, 22)])
figure("CALF_ONE_LEG", [calf_step(-28, True), calf_step(55, True)], ["side", "threeq_low"], props=[box(-14, 14, 0, STEP_H, 2, 22)])

# ── Step-ups ──
def stepup(h, name):
    figure(name, [
        dict(hip=(0, 38.5, 2), pitch=8, legL=ik((5, h, 18), (0, 0, 1)), legR=ik((-5, 0, 0), (0, 0, 1)), armL=fk(-10, 6, 20), armR=fk(30, 6, 30)),
        dict(hip=(0, 40 + h - 1.5, 18), legL=ik((5, h, 18), (0, 0, 1)), legR=ik((-5, h, 18), (0, 0, 1)), armL=fk(5, 6, 12), armR=fk(5, 6, 12)),
    ], ["side", "front"], props=[box(-14, 14, 0, h, 9, 33)])
stepup(9, "STEPUP_LOW")
alias("STEPUP_SLOW", "STEPUP", 3600)

# ── Planks ──
figure("PLANK_HOLD", [forearm_plank()], ["side", "high"], props=[mat()])
figure("PLANK_LEG_LIFT", [forearm_plank(), forearm_plank(dict(legR=fk(-10, 2, 0))), forearm_plank(), forearm_plank(dict(legL=fk(-10, 2, 0), anchor=("toR", (0, 0, -40), "yz")))],
       ["side", "high"], props=[mat()], loop=True, ms=4000)

def knee_plank():
    p = 90 - math.degrees(math.asin((13.5 - 2) / (TORSO + THIGH)))
    s = dict(pitch=p, legL=fk(0, 2, 95), legR=fk(0, 2, 95), feet="line", anchor=("knL", (0, 2, -20), "yz"), head=10)
    j = build(s).joints()
    s["armL"] = ik((4, 0, j["shL"][2] + 13), (0.1, -1, 0))
    s["armR"] = ik((-4, 0, j["shR"][2] + 13), (-0.1, -1, 0))
    return s
figure("KNEE_PLANK", [knee_plank()], ["side", "high"], props=[mat()])

def long_plank():
    p = plank_line(11)
    s = dict(pitch=p, legL=fk(0, 2, 0), legR=fk(0, 2, 0), feet="shin", anchor=("toL", (0, 0, -40), "yz"), head=10)
    j = build(s).joints()
    s["armL"] = ik((4, 0, j["shL"][2] + 22), (0.1, -1, 0.3))
    s["armR"] = ik((-4, 0, j["shR"][2] + 22), (-0.1, -1, 0.3))
    return s
figure("LONG_PLANK", [long_plank()], ["side", "high"], props=[mat()])

def knee_side_plank(top_arm):
    s = dict(roll=-90 + 26, hip=(0, 0, 0), legL=fk(0, 0, 90), legR=fk(0, 0, 90), feet="line",
             anchor=("knR", (-22, 1.5, 0), "xy"), armL=top_arm)
    j = build(s).joints()
    s["armR"] = ik((j["shR"][0] + 1, 0, j["shR"][2] + 12), (0, -1, 0))
    return s
figure("KNEE_SIDEPLANK", [knee_side_plank(fk(0, 8, 0)), knee_side_plank(fk(0, 92, 0))], ["front", "above"], props=[mat()])
figure("SIDEPLANK_LEG", [side_plank(fk(0, 8, 0)), side_plank(fk(0, 8, 0), top_leg=fk(0, 32, 0))], ["front", "above"], props=[mat()])

# ── Dead bug and bird dog ──
STRAIGHT_UP, LOW_LEG = fk(90, 4, 0), fk(15, 4, 0)
figure("DEADBUG_STRAIGHT", [
    supine(UP_ARM, UP_ARM, STRAIGHT_UP, STRAIGHT_UP),
    supine(OVER, UP_ARM, STRAIGHT_UP, LOW_LEG),
    supine(UP_ARM, UP_ARM, STRAIGHT_UP, STRAIGHT_UP),
    supine(UP_ARM, OVER, LOW_LEG, STRAIGHT_UP),
], ["side", "high"], props=[mat()], loop=True, ms=4000)

def bird_crunch(arm, leg):
    s = quad()
    s[arm] = fk(25, 4, 120)
    s[leg] = fk(95, 2, 120)
    s["hip"] = (0, 20.3, 0)
    return s
figure("BIRDDOG_CRUNCH", [quad(), quad("armL", "legR"), bird_crunch("armL", "legR"), quad("armL", "legR")],
       ["side", "high"], props=[mat()], loop=True, ms=4400)

def superman(up):
    return dict(pitch=90 - (8 if up else 0), hip=(0, 4.5, 0), anchor=("pelvis", (0, 4.5, 0), "y"), head=-15 if up else 0,
                armL=fk(192 if up else 178, 12, 0), armR=fk(192 if up else 178, 12, 0),
                legL=fk(-12 if up else 0, 3, 0), legR=fk(-12 if up else 0, 3, 0), feet="line")
figure("SUPERMAN", [superman(False), superman(True)], ["side", "high"], props=[mat()])

# ── Bag ──
def hook(side):
    s = boxing()
    if side == "L":
        s["armL"] = ik((-2, 67, BAG_Z - 10), (1, 0.1, 0), planted=False); s["yaw"] = -40
    else:
        s["armR"] = ik((2, 67, BAG_Z - 10), (-1, 0.1, 0), planted=False); s["yaw"] = 20; s["feetR"] = 10
    return s
figure("BAG_HOOKS", [boxing(), boxing(jab=True), boxing(), boxing(cross=True), boxing(), hook("L"), boxing(), hook("R")],
       ["side", "back3q"], props=[bag(0, BAG_Z, 9, 32, 92)], loop=True, ms=3200)

# ── Bear ──
figure("BEAR_HOLD", [bear(0)], ["side", "high"], props=[mat()])
def bear_side(step):
    p = 90 - math.degrees(math.asin((26 - 22) / TORSO))
    s = dict(pitch=p, hip=(5 * step, 22, 0), head=-15, feet="shin")
    j = build(dict(s, hip=(0, 22, 0))).joints()
    for side, sign in (("L", 1), ("R", -1)):
        dx = (6 * step) if (step > 0) == (sign > 0) else 0
        s["arm" + side] = ik((j["sh" + side][0] + 1.5 * sign + dx, 0, j["sh" + side][2]), (0, 0, -1), planted=False)
        s["leg" + side] = ik((j["hip" + side][0] + dx, 5.5, j["hip" + side][2] - 15), (0, -0.3, 1), planted=False)
    return s
figure("BEAR_LATERAL", [bear_side(0), bear_side(1), bear_side(0), bear_side(-1)], ["front", "high"], props=[mat()], loop=True, ms=2800)

# ── Handstands ──
# Upside down: pitch 180 points the head at the floor and the chest toward -z. Below 180 the feet
# lean toward the chest side, above 180 toward the back.
def inverted(pitch, legs=fk(0, 3, 0), feet="line", head=-20, hands_z=0.0):
    arms = fk(180, 0, 0)
    return dict(pitch=pitch, head=head, armL=arms, armR=arms, legL=legs, legR=legs, feet=feet,
                anchor=("haL", (0, 0, hands_z), "yz"))

def toes_z(s):
    j = build(s).joints()
    return min(j["toL"][2], j["toR"][2]), max(j["toL"][2], j["toR"][2])

def wall(z0, z1, top=100): return box(-20, 20, 0, top, z0, z1)

# Wall L: hands on the floor, hips over the shoulders, legs level with the feet flat on the wall.
s = inverted(180, legs=fk(90, 3, 0), feet="shin")
zf, _ = toes_z(s)
j = build(s).joints()
zw = min(j["ftL"][2], j["ftR"][2]) - 0.8
figure("HS_WALL_L", [s], ["side", "back"], props=[wall(zw - 2, zw, 66)])

# Chest to the wall: walked up until nearly vertical, toes resting on the wall.
s = inverted(176)
zf, _ = toes_z(s)
figure("HS_CHEST_WALL", [s], ["side", "back"], props=[wall(zf - 2.5, zf - 0.5)])

# Back to the wall: kicked up, heels resting on it, hands a short way out from it.
s = inverted(184, legs=fk(-4, 3, 0))
_, zb = toes_z(s)
j = build(s).joints()
zh = max(j["ftL"][2], j["ftR"][2]) + 0.8
figure("HS_BACK_WALL", [s], ["side", "front"], props=[wall(zh, zh + 2)])

# Free: a small sway over the hands, kept up by the fingers and the wrists.
figure("HANDSTAND", [inverted(178.5), inverted(181.5)], ["side", "front"], props=[mat()], ms=1800)


# ───────────────────────────── frames, fitting, checks ─────────────────────────────

def frames_of(name, misses):
    f = FIG[name]
    keys = f["keys"]
    built = [build(k, misses, f"{name} key {i}") for i, k in enumerate(keys)]
    out = []
    n = len(keys)
    pairs = range(n) if f["loop"] else range(n - 1)
    for i in pairs:
        a, b = built[i], built[(i + 1) % n]
        sa, sb = keys[i], keys[(i + 1) % n]
        for s in range(f["steps"]):
            t = s / f["steps"]
            t = t * t * (3 - 2 * t)
            out.append(blend(a, b, t, sa, sb))
    if not f["loop"]:
        out.append(built[-1])
    if n == 1:
        out = [built[0]]
    return out


def view_data(name, view, frames):
    f = FIG[name]
    cam = camera(view)
    joints = [fr.joints() for fr in frames]
    pts = [{k: project(v, cam) for k, v in j.items()} for j in joints]
    props2d = []
    for pr in f["props"]:
        if pr[0] == "bar":
            a, b = project(pr[1], cam), project(pr[2], cam)
            if np.hypot(*(a - b)[:2]) < 3:
                props2d.append(("dot", a[0], a[1], 2.0))
            else:
                props2d.append(("line", a[0], a[1], b[0], b[1], 2.2, False))
        elif pr[0] == "box":
            x0, x1, y0, y1, z0, z1 = pr[1]
            c = [np.array([x, y, z]) for x in (x0, x1) for y in (y0, y1) for z in (z0, z1)]
            edges = [(i, j) for i in range(8) for j in range(i + 1, 8) if bin(i ^ j).count("1") == 1]
            seen = set()
            for i, j in edges:
                a, b = project(c[i], cam), project(c[j], cam)
                if np.hypot(*(a - b)[:2]) < 0.5: continue
                key = tuple(np.round([a[0], a[1], b[0], b[1]], 1))
                if key in seen: continue
                seen.add(key)
                props2d.append(("line", a[0], a[1], b[0], b[1], 1.0, False))
        elif pr[0] == "bag":
            _, x, z, r, y0, y1 = pr
            c = project(np.array([x, (y0 + y1) / 2, z]), cam)
            top = project(np.array([x, y1, z]), cam); bot = project(np.array([x, y0, z]), cam)
            props2d.append(("bag", c[0] - r, top[1], 2 * r, top[1] - bot[1]))
            chain = project(np.array([x, y1 + 12, z]), cam)
            props2d.append(("line", top[0], top[1], chain[0], chain[1], 0.8, False))
        elif pr[0] == "door":
            _, z, half, height, (hx, hy) = pr
            c = [np.array(v, float) for v in ((-half, 0, z), (half, 0, z), (half, height, z), (-half, height, z))]
            for i in range(4):
                a, b = project(c[i], cam), project(c[(i + 1) % 4], cam)
                if np.hypot(*(a - b)[:2]) < 0.5:
                    continue
                props2d.append(("line", a[0], a[1], b[0], b[1], 1.0, False))
            if np.hypot(*(project(c[0], cam) - project(c[1], cam))[:2]) < 0.5:
                a, b = project(c[0], cam), project(c[3], cam)
                props2d.append(("line", a[0], a[1], b[0], b[1], 2.0, False))
        elif pr[0] == "tether":
            p = project(pr[2], cam)
            props2d.append(("tether", pr[1], p[0], p[1]))
        elif pr[0] == "mat":
            if pr[1] is None:
                allj = [v for fr in frames for v in fr.joints().values()]
                x0, x1 = min(v[0] for v in allj) - 6, max(v[0] for v in allj) + 6
                z0, z1 = min(v[2] for v in allj) - 6, max(v[2] for v in allj) + 6
            else:
                x0, x1, z0, z1 = pr[1]
            c = [np.array(v, float) for v in ((x0, 0, z0), (x1, 0, z0), (x1, 0, z1), (x0, 0, z1))]
            for i in range(4):
                a, b = project(c[i], cam), project(c[(i + 1) % 4], cam)
                if np.hypot(*(a - b)[:2]) < 0.5: continue
                props2d.append(("line", a[0], a[1], b[0], b[1], 0.7, True))
    floor_line = VIEWS[view][1] == 0 and not f["hanging"]
    # Fit: one scale for every frame of this view, at most life size (1 unit = 1 box unit).
    xs, ys = [], []
    for p in pts:
        for k, v in p.items():
            r = HEAD_R if k == "head" else 0
            xs += [v[0] - r, v[0] + r]; ys += [v[1] - r, v[1] + r]
    for pr in props2d:
        if pr[0] == "line": xs += [pr[1], pr[3]]; ys += [pr[2], pr[4]]
        elif pr[0] in ("dot", "tether"): xs.append(pr[-3] if pr[0] == "dot" else pr[2]); ys.append(pr[-2] if pr[0] == "dot" else pr[3])
        elif pr[0] == "bag": xs += [pr[1], pr[1] + pr[3]]; ys += [pr[2], pr[2] - pr[4]]
    if floor_line:
        ys.append(0.0)
    w, h = max(xs) - min(xs), max(ys) - min(ys)
    s = min(1.0, 92 / w, 92 / h)
    if floor_line:
        s = min(s, 88 / h)  # the floor sits at 90, so keep the top inside the box
    cx = (max(xs) + min(xs)) / 2
    if floor_line:
        ox, oy = 50 - cx * s, 90.0  # floor (y = 0) at 90
        def T(x, y): return (ox + x * s, oy - y * s)
    else:
        cy = (max(ys) + min(ys)) / 2
        def T(x, y): return (50 + (x - cx) * s, 52 - (y - cy) * s)
    out_frames = []
    for p in pts:
        out_frames.append({k: (*T(v[0], v[1]), v[2]) for k, v in p.items()})
    out_props = []
    for pr in props2d:
        if pr[0] == "line":
            a, b = T(pr[1], pr[2]), T(pr[3], pr[4])
            out_props.append(("line", *a, *b, pr[5], pr[6]))
        elif pr[0] == "dot":
            out_props.append(("dot", *T(pr[1], pr[2]), pr[3]))
        elif pr[0] == "bag":
            a = T(pr[1], pr[2])
            out_props.append(("bag", a[0], a[1], pr[3] * s, pr[4] * s))
        elif pr[0] == "tether":
            out_props.append(("tether", pr[1], *T(pr[2], pr[3])))
    return dict(frames=out_frames, props=out_props, floor=floor_line, scale=s, label=VIEWS[view][2],
                loop=f["loop"], ms=f["ms"] or 1600)


def check(name, frames):
    bad = []
    f = FIG[name]
    for i, fr in enumerate(frames):
        j = fr.joints()
        low = min(v[1] for k, v in j.items() if k != "head")
        if j["head"][1] - HEAD_R < -0.6:
            bad.append(f"{name} frame {i}: head below the floor")
        if low < -0.6:
            worst = min(j, key=lambda k: j[k][1])
            bad.append(f"{name} frame {i}: {worst} below the floor ({low:.1f})")
        # Knees and elbows bend the natural way.
        for s in "LR":
            th, sh = unit(j["kn" + s] - j["hip" + s]), unit(j["ft" + s] - j["kn" + s])
            hinge = np.cross(th, sh)
            if np.linalg.norm(hinge) > 0.2 and np.dot(hinge, fr.left) < -0.1:
                bad.append(f"{name} frame {i}: knee {s} bends backward")
    return bad


# ───────────────────────────── outputs ─────────────────────────────

ORDER = ["head", "eyeL", "eyeR", "neck", "shL", "elL", "haL", "shR", "elR", "haR",
         "pelvis", "hipL", "knL", "ftL", "toL", "hipR", "knR", "ftR", "toR"]

SEGMENTS = [("neck", "head"), ("neck", "pelvis"), ("shL", "shR"), ("hipL", "hipR"), ("shL", "elL"), ("elL", "haL"), ("shR", "elR"), ("elR", "haR"),
            ("hipL", "knL"), ("knL", "ftL"), ("ftL", "toL"), ("hipR", "knR"), ("knR", "ftR"), ("ftR", "toR")]


def draw_pose(img, ox, oy, s, data, idx, color):
    d = ImageDraw.Draw(img, "RGBA")
    T = lambda x, y: (ox + x * s, oy + y * s)
    if data["floor"]:
        d.line([T(0, 90), T(100, 90)], fill=(110, 110, 110), width=2)
    p = data["frames"][idx]
    for pr in data["props"]:
        if pr[0] == "line":
            d.line([T(pr[1], pr[2]), T(pr[3], pr[4])], fill=(90, 96, 110) if pr[6] else (160, 160, 160), width=max(1, int(pr[5] * s)))
        elif pr[0] == "dot":
            x, y = T(pr[1], pr[2]); r = pr[3] * s
            d.ellipse([x - r, y - r, x + r, y + r], fill=(170, 170, 170))
        elif pr[0] == "bag":
            d.rounded_rectangle([T(pr[1], pr[2]), T(pr[1] + pr[3], pr[2] + pr[4])], int(5 * s), outline=(208, 128, 80), width=3)
        elif pr[0] == "tether":
            d.line([T(*p[pr[1]][:2]), T(pr[2], pr[3])], fill=(220, 200, 120), width=2)
    ref = p["pelvis"][2]
    sc = data["scale"]
    items = []
    quad = [p["shL"], p["shR"], p["hipR"], p["hipL"]]
    items.append((sum(q[2] for q in quad) / 4 + 0.01, "torso", None))
    for a, b in SEGMENTS:
        items.append(((p[a][2] + p[b][2]) / 2, "seg", (a, b)))
    items.append((p["head"][2], "head", None))
    for dep, kind, ab in sorted(items, key=lambda x: -x[0]):
        t = max(0.0, min(1.0, (dep - ref) / 9))
        alpha = int(255 * (1 - 0.55 * t))
        c = color + (alpha,)
        if kind == "torso":
            d.polygon([T(*q[:2]) for q in quad], fill=color + (60,))
        elif kind == "seg":
            a, b = ab
            wid = 3 if b.startswith("to") else 4
            d.line([T(*p[a][:2]), T(*p[b][:2])], fill=c, width=max(2, int(wid * sc * s / 2.4 * 1.3)))
        else:
            hx, hy = T(*p["head"][:2]); r = HEAD_R * sc * s
            d.ellipse([hx - r, hy - r, hx + r, hy + r], fill=(20, 22, 26, 255), outline=c, width=3)
            for e in ("eyeL", "eyeR"):
                if p[e][2] < p["head"][2] - 0.8:
                    ex, ey = T(*p[e][:2])
                    d.ellipse([ex - 2, ey - 2, ex + 2, ey + 2], fill=c)


def sheet(path, all_views):
    s = 2.2; cell = int(104 * s) + 16
    rows = []
    for name, views in all_views.items():
        for vd in views:
            n = len(vd["frames"])
            idxs = sorted(set([0, n // 4, n // 2, (3 * n) // 4, n - 1])) if vd["loop"] else sorted(set([0, n // 2, n - 1]))
            rows.append((name, vd, idxs))
    cols = 5
    img = Image.new("RGB", (cell * cols + 150, cell * len(rows)), (20, 22, 26))
    d = ImageDraw.Draw(img)
    for r, (name, vd, idxs) in enumerate(rows):
        d.text((8, r * cell + 10), f"{name}\n{vd['label']}", fill=(255, 255, 255))
        for c, i in enumerate(idxs):
            draw_pose(img, 150 + c * cell + 8, r * cell + 8, s, vd, i, (143, 180, 255))
    img.save(path)


def kotlin(path, all_views):
    # Joint data goes in strings parsed on first use: as float literals it would pass the JVM's
    # 64 KB limit for one method.
    out = ["package com.dmeltz.personalworkout.core", "",
           "// Generated by tools/gen_figures.py from a 3D body model. Edit the poses there and run it again.", "",
           "object Figures {",
           "    /** Frames separated by ';', each joint's x, y and depth separated by ','. */",
           "    private fun frames(data: String): List<Pose> =",
           "        data.split(';').map { f -> Pose(f.split(',').map { it.toFloat() }.toFloatArray()) }",
           "",
           "    val all: Map<String, Figure> by lazy {",
           "        mapOf("]
    def num(v):
        t = f"{v:.1f}"
        return "0" if t in ("0.0", "-0.0") else t.rstrip("0").rstrip(".") if "." in t else t
    def fmt(p): return ",".join(f"{num(p[k][0])},{num(p[k][1])},{num(p[k][2])}" for k in ORDER)
    def prop(pr):
        if pr[0] == "line": return f"Prop.Line({pr[1]:.1f}f, {pr[2]:.1f}f, {pr[3]:.1f}f, {pr[4]:.1f}f, {pr[5]:.1f}f, {str(pr[6]).lower()})"
        if pr[0] == "dot": return f"Prop.Dot({pr[1]:.1f}f, {pr[2]:.1f}f, {pr[3]:.1f}f)"
        if pr[0] == "bag": return f"Prop.Bag({pr[1]:.1f}f, {pr[2]:.1f}f, {pr[3]:.1f}f, {pr[4]:.1f}f)"
        if pr[0] == "tether": return f"Prop.Tether(Pose.{JOINT_CONST[pr[1]]}, {pr[2]:.1f}f, {pr[3]:.1f}f)"
    for name, views in all_views.items():
        for i, vd in enumerate(views):
            key = name if i == 0 else f"{name}_{i + 1}"
            out.append(f'            "{key}" to Figure(')
            out.append(f'                frames("{";".join(fmt(p) for p in vd["frames"])}"),')
            out.append(f"                listOf({', '.join(prop(p) for p in vd['props'])}),")
            out.append(f'                floor = {str(vd["floor"]).lower()}, scale = {vd["scale"]:.2f}f, label = "{vd["label"]}", '
                       f'loop = {str(vd["loop"]).lower()}, durationMs = {vd["ms"]},')
            out.append("            ),")
    out += ["        )", "    }", "}", ""]
    open(path, "w").write("\n".join(out))


JOINT_CONST = {"haL": "HAND_L", "haR": "HAND_R"}

if __name__ == "__main__":
    misses, problems, all_views = [], [], {}
    for name in FIG:
        frames = frames_of(name, misses)
        problems += check(name, frames)
        all_views[name] = [view_data(name, v, frames) for v in FIG[name]["views"]]
    for m in misses + problems:
        print("CHECK:", m)
    sheet(sys.argv[1], all_views)
    if len(sys.argv) > 2:
        kotlin(sys.argv[2], all_views)
