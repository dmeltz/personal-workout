"""Builds the exercise stick figures by forward kinematics, writes Figures.kt and a preview PNG.

Run from the repository root:
  python3 tools/gen_figures.py /tmp/figures.png core/src/main/kotlin/com/dmeltz/personalworkout/core/Figures.kt
Names match the "figure:" lines in app/src/main/assets/exercises.txt.

Screen coordinates, 100x100 box, y down, floor at y=90. Angles in degrees: 0 right, 90 down, 180 left, -90 up.
"""
import math, sys
from PIL import Image, ImageDraw

L = dict(torso=28, neck=8, thigh=20, shin=20, upper=13, fore=13)
FLOOR = 90

def at(p, ang, length):
    r = math.radians(ang)
    return (p[0] + length * math.cos(r), p[1] + length * math.sin(r))

def pose(torso, arm, leg, arm2=None, leg2=None, head=None, anchor=("foot", (50, FLOOR))):
    """arm/leg are (upper angle, lower angle). Returns joint dict, shifted so anchor joint sits at anchor point."""
    hip = (0.0, 0.0)
    neck = at(hip, torso, L["torso"])
    j = dict(hip=hip, neck=neck)
    j["head"] = at(neck, torso if head is None else head, L["neck"])
    j["elbow"] = at(neck, arm[0], L["upper"]); j["hand"] = at(j["elbow"], arm[1], L["fore"])
    j["knee"] = at(hip, leg[0], L["thigh"]); j["foot"] = at(j["knee"], leg[1], L["shin"])
    a2 = arm2 or arm; l2 = leg2 or leg
    j["elbow2"] = at(neck, a2[0], L["upper"]); j["hand2"] = at(j["elbow2"], a2[1], L["fore"])
    j["knee2"] = at(hip, l2[0], L["thigh"]); j["foot2"] = at(j["knee2"], l2[1], L["shin"])
    name, (ax, ay) = anchor
    dx, dy = ax - j[name][0], ay - j[name][1]
    return {k: (v[0] + dx, v[1] + dy) for k, v in j.items()}

S = 90  # straight down
F = {}
# Each figure: (start pose, end pose, props). Props: ("bar", y), ("box", x, y, w, h), ("bag", x, y, w, h), ("wall", x)
F["PUSHUP"] = (
    pose(-21, (90, 90), (159, 159), anchor=("hand", (70, FLOOR))),
    pose(-5, (200, 53), (175, 175), anchor=("hand", (70, FLOOR))),
    [],
)
F["PIKE"] = (
    pose(48, (60, 60), (106, 106), head=60, anchor=("hand", (70, FLOOR))),
    pose(62, (200, 70), (118, 118), head=70, anchor=("hand", (70, FLOOR))),
    [],
)
F["PULLUP"] = (
    pose(-90, (-90, -90), (100, 160), anchor=("hand", (50, 6))),
    pose(-90, (-150, -40), (100, 160), arm2=(-30, -140), anchor=("hand", (40, 6))),
    [("bar", 6), ("nofloor",)],
)
F["HANG"] = (
    pose(-90, (-90, -90), (95, 95), anchor=("hand", (50, 6))),
    pose(-90, (-93, -90), (97, 97), anchor=("hand", (50, 9))),
    [("bar", 6), ("nofloor",)],
)
F["ROW"] = (
    pose(-118, (-20, -20), (62, 62), anchor=("foot", (74, FLOOR))),
    pose(-102, (150, -35), (78, 78), anchor=("foot", (74, FLOOR))),
    [("door", 82, 48)],
)
F["SQUAT"] = (
    pose(-90, (0, 0), (90, 90), anchor=("foot", (50, FLOOR))),
    pose(-60, (0, 0), (30, 120), anchor=("foot", (50, FLOOR))),
    [],
)
F["SPLIT"] = (
    pose(-90, (90, 90), (72, 100), leg2=(110, 118), anchor=("foot", (60, FLOOR))),
    pose(-88, (90, 90), (15, 90), leg2=(92, 175), anchor=("foot", (60, FLOOR))),
    [],
)
F["BRIDGE"] = (
    pose(180, (0, 0), (-45, 68), anchor=("neck", (30, FLOOR - 3))),
    pose(160, (5, 0), (-20, 85), anchor=("neck", (30, FLOOR - 3))),
    [],
)
F["CALF"] = (
    pose(-90, (90, 90), (90, 90), anchor=("foot", (50, FLOOR))),
    pose(-90, (90, 90), (90, 90), anchor=("foot", (50, FLOOR - 4))),
    [],
)
F["STEPUP"] = (
    pose(-85, (90, 90), (20, 90), leg2=(95, 95), anchor=("foot2", (40, FLOOR))),
    pose(-90, (90, 90), (90, 90), leg2=(80, 130), anchor=("foot", (62, FLOOR - 16))),
    [("box", 52, FLOOR - 16, 26, 16)],
)
F["PLANK"] = (
    pose(-8, (90, 0), (172, 172), anchor=("hand", (84, FLOOR))),
    pose(-8, (90, 0), (172, 172), anchor=("hand", (84, FLOOR))),
    [],
)
F["SIDEPLANK"] = (
    pose(-20, (90, 90), (160, 160), arm2=(-90, -90), anchor=("hand", (70, FLOOR))),
    pose(-20, (90, 90), (160, 160), arm2=(-90, -90), leg2=(176, 176), anchor=("hand", (70, FLOOR))),
    [],
)
F["KNEERAISE"] = (
    pose(-90, (-90, -90), (90, 90), anchor=("hand", (50, 6))),
    pose(-97, (-85, -90), (-10, 90), anchor=("hand", (50, 6))),
    [("bar", 6), ("nofloor",)],
)
F["DEADBUG"] = (
    pose(180, (-90, -90), (-90, 0), anchor=("hip", (58, FLOOR - 3))),
    pose(180, (-90, -90), (-90, 0), arm2=(180, 180), leg2=(-5, -5), anchor=("hip", (58, FLOOR - 3))),
    [],
)
F["BIRDDOG"] = (
    pose(-12, (90, 90), (90, 180), anchor=("knee", (40, FLOOR))),
    pose(-12, (-5, -5), (90, 180), arm2=(90, 90), leg2=(175, 175), anchor=("knee", (40, FLOOR))),
    [],
)
F["BAG"] = (
    pose(-85, (60, -60), (100, 80), leg2=(75, 100), anchor=("foot", (30, FLOOR))),
    pose(-80, (0, 0), (100, 80), arm2=(60, -60), leg2=(75, 100), anchor=("foot", (30, FLOOR))),
    [("bag", 66, 10, 16, 52)],
)
F["BEAR"] = (
    pose(0, (90, 90), (80, 160), leg2=(105, 165), anchor=("hand", (72, FLOOR))),
    pose(0, (80, 100), (105, 165), arm2=(100, 80), leg2=(80, 160), anchor=("hand", (74, FLOOR))),
    [],
)

def check():
    bad = []
    for name, (a, b, props) in F.items():
        for tag, p in (("start", a), ("end", b)):
            for k, (x, y) in p.items():
                limit = 104 if ("nofloor",) in props else FLOOR + 2
                if not (-2 <= x <= 102 and -2 <= y <= limit):
                    bad.append(f"{name} {tag} {k} ({x:.0f},{y:.0f})")
    return bad

def draw(img, ox, oy, s, p, props, color):
    d = ImageDraw.Draw(img)
    T = lambda q: (ox + q[0] * s, oy + q[1] * s)
    if ("nofloor",) not in props: d.line([T((0, FLOOR)), T((100, FLOOR))], fill=(120, 120, 120), width=2)
    for pr in props:
        if pr[0] == "bar": d.line([T((25, pr[1])), T((75, pr[1]))], fill=(160, 160, 160), width=4)
        if pr[0] == "box": d.rectangle([T((pr[1], pr[2])), T((pr[1] + pr[3], pr[2] + pr[4]))], outline=(160, 160, 160), width=2)
        if pr[0] == "bag": d.rounded_rectangle([T((pr[1], pr[2])), T((pr[1] + pr[3], pr[2] + pr[4]))], 8, outline=(200, 120, 80), width=3)
        if pr[0] == "door":
            d.rectangle([T((pr[1], 4)), T((pr[1] + 12, FLOOR))], outline=(160, 160, 160), width=2)
            d.line([T(p["hand"]), T((pr[1], pr[2]))], fill=(220, 200, 120), width=2)
        if pr[0] == "wall": d.line([T((pr[1], 0)), T((pr[1], FLOOR))], fill=(160, 160, 160), width=4)
    for a, b in [("neck", "hip"), ("neck", "elbow2"), ("elbow2", "hand2"), ("hip", "knee2"), ("knee2", "foot2")]:
        d.line([T(p[a]), T(p[b])], fill=tuple(int(c * 0.6) for c in color), width=5)
    for a, b in [("neck", "hip"), ("neck", "elbow"), ("elbow", "hand"), ("hip", "knee"), ("knee", "foot")]:
        d.line([T(p[a]), T(p[b])], fill=color, width=5)
    hx, hy = T(p["head"]); r = 5 * s
    d.ellipse([hx - r, hy - r, hx + r, hy + r], outline=color, width=4)

def preview(path):
    s = 2.4; cell = int(106 * s) + 20
    names = list(F)
    img = Image.new("RGB", (cell * 2 * 3, cell * math.ceil(len(names) / 3)), (20, 22, 26))
    for i, n in enumerate(names):
        a, b, props = F[n]
        ox = (i % 3) * cell * 2; oy = (i // 3) * cell
        draw(img, ox + 10, oy + 10, s, a, props, (143, 180, 255))
        draw(img, ox + cell + 10, oy + 10, s, b, props, (123, 216, 143))
        ImageDraw.Draw(img).text((ox + 12, oy + 4), n, fill=(255, 255, 255))
    img.save(path)

def kotlin(path):
    order = ["head", "neck", "hip", "elbow", "hand", "elbow2", "hand2", "knee", "foot", "knee2", "foot2"]
    out = ["package com.dmeltz.personalworkout.core", "",
           "// Generated by tools/gen_figures.py. Edit the poses there and run it again.", "",
           "object Figures {", "    val all: Map<String, Figure> = mapOf("]
    def fmt(p): return "Pose(floatArrayOf(" + ", ".join(f"{p[k][0]:.1f}f, {p[k][1]:.1f}f" for k in order) + "))"
    def prop(pr):
        if pr[0] == "bar": return f"Prop.Bar({pr[1]}f)"
        if pr[0] == "wall": return f"Prop.Wall({pr[1]}f)"
        if pr[0] == "nofloor": return "Prop.NoFloor"
        if pr[0] == "door": return f"Prop.Door({pr[1]}f, {pr[2]}f)"
        kind = "Box" if pr[0] == "box" else "Bag"
        return f"Prop.{kind}({pr[1]}f, {pr[2]}f, {pr[3]}f, {pr[4]}f)"
    for n, (a, b, props) in F.items():
        out.append(f'        "{n}" to Figure(')
        out.append(f"            {fmt(a)},")
        out.append(f"            {fmt(b)},")
        out.append(f"            listOf({', '.join(prop(p) for p in props)}),")
        out.append("        ),")
    out += ["    )", "}", ""]
    open(path, "w").write("\n".join(out))

if __name__ == "__main__":
    for b in check(): print("OUT OF BOX:", b)
    preview(sys.argv[1])
    if len(sys.argv) > 2: kotlin(sys.argv[2])
