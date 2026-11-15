"""멜로드 첫 화면 배경(자체 제작): 해질녘 제주 해안도로. numpy로 직접 렌더링한다(외부 이미지·생성형 AI 미사용).
출력: 2560x1600 WebP. 앱은 같은 기하 상수(HomeSceneGeometry)로 자동차·빛을 이미지 위에 정확히 겹친다."""
import sys, numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter, zoom

OUT = sys.argv[1]
W, H = 2560, 1600
rng = np.random.default_rng(20261115)

# ---- 기하(앱의 HomeSceneGeometry와 같은 값) ----
HY = 0.40      # 지평선 높이(비율)
VX = 0.53      # 길 소실점 x
SX = 0.40      # 해 x
SUN_R = 0.045  # 해 반지름(폭 비율)
ROAD_HW = 0.40 # 화면 아래에서 길 반폭(폭 비율)

yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
fx, fy = xx / W, yy / H
img = np.zeros((H, W, 3), np.float32)

def mix(a, b, t):
    t = np.clip(t, 0, 1)[..., None]
    return a * (1 - t) + b * t
def C(*rgb): return np.array(rgb, np.float32) / 255.0
def smooth(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1); return t * t * (3 - 2 * t)

def fbm(scale_x, scale_y, octaves=5, seed=0):
    r = np.random.default_rng(seed)
    out = np.zeros((H, W), np.float32); amp = 1.0; tot = 0
    for o in range(octaves):
        gh = max(2, int(H / scale_y * 2 ** o)); gw = max(2, int(W / scale_x * 2 ** o))
        g = r.random((gh, gw)).astype(np.float32)
        up = zoom(g, (H / gh, W / gw), order=3)[:H, :W]
        out += up * amp; tot += amp; amp *= 0.5
    out /= tot
    return (out - out.min()) / (out.max() - out.min())

# ---- 하늘 ----
t = fy / HY
sky = mix(C(8, 24, 54), C(28, 70, 128), smooth(0.0, 0.55, t))
sky = mix(sky, C(110, 150, 200), smooth(0.45, 0.85, t))
sky = mix(sky, C(238, 222, 210), smooth(0.80, 1.0, t))
img[:] = sky

# 구름: 가로로 길게 늘어진 층운, 아래쪽이 노을빛으로 물든다
n = fbm(900, 160, 5, seed=3)
cloud_band = smooth(0.08, 0.30, fy) * (1 - smooth(0.30, 0.38, fy))
cl = smooth(0.46, 0.74, n) * cloud_band
lit = smooth(0.15, 0.38, fy)
cloud_col = mix(C(40, 70, 115), C(250, 214, 190), lit)
img = mix(img, cloud_col, cl * 0.85)

# 해 주변 빛과 빛줄기
dx = (fx - SX) * W / H; dy = (fy - HY)
d = np.sqrt(dx * dx + dy * dy)
ang = np.arctan2(dy, dx)
rays = (0.5 + 0.5 * np.cos(ang * 13 + 0.7)) ** 12 * np.exp(-d / 0.22) * (fy < HY)
glow = np.exp(-(d / 0.08) ** 2) * 0.75 + np.exp(-(d / 0.32) ** 1.4) * 0.40
warm = C(255, 238, 214)
img = img + warm * (glow[..., None] * (fy < HY + 0.002)[..., None]) * 0.50 + warm * rays[..., None] * 0.035

# 오름(오른쪽의 부드러운 언덕 실루엣, 먼 것은 옅게)
def ridge(xs, bumps):
    h = np.zeros_like(xs)
    for cx, w, a in bumps: h += a * np.exp(-((xs - cx) / w) ** 2)
    return h
xs = fx[0]
layers = [
    ([(0.62, 0.05, 0.020), (0.72, 0.07, 0.034), (0.86, 0.06, 0.026), (0.97, 0.05, 0.022)], C(120, 148, 186)),
    ([(0.68, 0.04, 0.016), (0.80, 0.05, 0.024), (0.93, 0.07, 0.030)], C(78, 104, 146)),
]
for bumps, col in layers:
    h = ridge(xs, bumps)
    top = HY - h
    mask = (fy >= top[None, :]) & (fy < HY) & (fx > 0.55)
    img[mask] = img[mask] * 0.25 + col * 0.75

# ---- 땅: 왼쪽 바다, 오른쪽 들 ----
g = smooth(HY, 1.0, fy)
below = fy >= HY
def road_hw(y): return ROAD_HW * np.clip((y - HY) / (1 - HY), 0, None)
cx_road = VX + (0.5 - VX) * np.clip((fy - HY) / (1 - HY), 0, None)
left_edge = cx_road - road_hw(fy)
right_edge = cx_road + road_hw(fy)
sea = (fx < left_edge) & below
land = (fx > right_edge) & below

sea_col = mix(C(150, 176, 206), C(10, 40, 82), smooth(0.0, 0.55, g))
img[sea] = sea_col[sea]
# 물결 무늬와 윤슬(해 아래로 퍼지는 빛 기둥)
waves = fbm(500, 18, 4, seed=7)
img[sea] += (waves[sea] - 0.5)[..., None] * 0.06
spark = fbm(60, 6, 2, seed=11)
col_w = 0.012 + 0.16 * g
path = np.exp(-((fx - SX) / col_w) ** 2)
glit = path * smooth(0.62, 0.80, spark) * (1.2 - g) * 1.4
img[sea] += (warm * glit[..., None])[sea]
img[sea] += (warm * (path * 0.18 * (1 - g))[..., None])[sea]

land_col = mix(C(112, 126, 142), C(22, 30, 40), smooth(0.0, 0.6, g))
img[land] = land_col[land]
grass = fbm(700, 40, 4, seed=5)
img[land] += (grass[land] - 0.5)[..., None] * 0.10

# ---- 길 ----
road = (fx >= left_edge) & (fx <= right_edge) & below
asphalt = mix(C(104, 118, 140), C(26, 34, 46), smooth(0.0, 0.5, g))
grain = rng.normal(0, 0.012, (H, W)).astype(np.float32)
img[road] = asphalt[road] + grain[road][..., None]
u = np.where(road, (fx - cx_road) / np.maximum(road_hw(fy), 1e-4), 0)   # -1..1 가로 위치

# 가운데 차선 경로 리본(현대 액티브 블루)
ribbon = road & (np.abs(u) < 0.30)
rib_a = (0.10 + 0.16 * g) * (1 - smooth(0.72, 1.0, fy) * 0.55)
img[ribbon] = mix(img, C(0, 170, 210), rib_a)[ribbon]
for sgn in (-1, 1):
    e = road & (np.abs(u - sgn * 0.30) < 0.012 + 0.004 * (1 - g))
    img[e] = mix(img, C(90, 215, 240), 0.55 * np.ones((H, W)))[e]

# 차선: 양끝 실선, 안쪽 점선(원근 간격)
depth = 1.0 / np.maximum((fy - HY) / (1 - HY), 1e-3)     # 1(아래)~큼(먼 곳)
for lx, dashed in ((-0.96, False), (0.96, False), (-0.34, True), (0.34, True)):
    m = road & (np.abs(u - lx) < 0.016)
    if dashed:
        m &= (np.mod(depth * 1.6, 1.0) < 0.55)
    img[m] = mix(img, C(236, 240, 246), 0.92 * np.ones((H, W)))[m]

# ---- 대기: 지평선 안개, 빛 번짐, 비네트, 입자 ----
haze = np.exp(-np.abs(fy - HY) / 0.05)
img = mix(img, C(236, 224, 214), haze * 0.55)
bright = np.clip(img - 0.82, 0, None)
bloom = np.stack([gaussian_filter(bright[..., k], 38) for k in range(3)], -1)
img = img + bloom * 1.15
sun_mask = (d < SUN_R * W / H * 0.95 * (H / W) * (W / H)) & (fy < HY)
dsun = np.sqrt(((fx - SX) * W) ** 2 + ((fy - HY) * H) ** 2) / W
disk = (dsun < SUN_R) & (fy <= HY)
img[disk] = mix(img, C(255, 250, 240), 0.95 * np.ones((H, W)))[disk]
vig = ((fx - 0.5) ** 2 * 1.2 + (fy - 0.45) ** 2 * 1.6)
img *= (1 - np.clip(vig, 0, 1) * 0.55)[..., None]
img += rng.normal(0, 0.008, (H, W, 1)).astype(np.float32)

out = (np.clip(img, 0, 1) ** (1 / 1.0) * 255).astype(np.uint8)
Image.fromarray(out).save(OUT, 'WEBP', quality=90, method=6)
Image.fromarray(out).resize((1280, 800), Image.LANCZOS).save('preview.jpg', quality=88)
print('ok')
