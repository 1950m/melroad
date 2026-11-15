"""VSCO 2 CE(CC0) 샘플을 앱용 모노 22.05kHz 16비트 PCM으로 변환하고 실제 음높이를 검출해 색인을 만든다."""
import sys, os, re, json
import numpy as np
from scipy.io import wavfile
from scipy.signal import resample_poly

SRC, DST = sys.argv[1], sys.argv[2]
OUT_SR = 22050
NAMES = {'C':0,'C#':1,'Db':1,'D':2,'D#':3,'Eb':3,'E':4,'F':5,'F#':6,'Gb':6,'G':7,'G#':8,'Ab':8,'A':9,'A#':10,'Bb':10,'B':11}

# 묶음: (경로 패턴, 최대 길이 초, 페이드 초, 목표 RMS dBFS)
BANKS = {
    'piano':  ('Keys/Upright Piano/', 4.5, 0.6, -20),
    'violins':('Strings/Violin Section/susVib/', 4.5, 0.4, -20),
    'violas': ('Strings/Viola Section/susvib/', 4.5, 0.4, -20),
    'cellos': ('Strings/Cello Section/susvib/', 4.5, 0.4, -20),
    'pizz':   ('Strings/Cello Section/pizzT/', 1.6, 0.3, -20),
    'harp':   ('Strings/Harp/', 3.5, 0.6, -20),
    'flute':  ('Woodwinds/Flute/expvib/', 4.0, 0.4, -20),
    'horn':   ('Brass/F Horn/sus/', 4.0, 0.4, -20),
    'timphit':('Percussion/Timpani/Timpani', 3.0, 0.6, -20),
    'timproll':('Percussion/Timpani/Rolls/', 4.5, 0.6, -22),
}

def load(path):
    sr, x = wavfile.read(path)
    if x.dtype == np.int16: x = x.astype(np.float32) / 32768
    elif x.dtype == np.int32: x = x.astype(np.float32) / 2147483648
    elif x.dtype == np.uint8: x = (x.astype(np.float32) - 128) / 128
    else: x = x.astype(np.float32)
    if x.ndim > 1: x = x.mean(axis=1)
    return sr, x

def f0(x, sr):
    """조화 곱 스펙트럼으로 기본 주파수 추정(안정 구간)."""
    n = min(len(x), int(sr * 1.0))
    seg = x[int(sr*0.15): int(sr*0.15) + n] if len(x) > int(sr*0.15) + n else x[:n]
    if len(seg) < 2048: return None
    w = seg * np.hanning(len(seg))
    nfft = 1 << 17
    spec = np.abs(np.fft.rfft(w, nfft))
    hps = spec.copy()
    for h in (2, 3):
        dec = spec[::h]
        hps[:len(dec)] *= dec
    freqs = np.fft.rfftfreq(nfft, 1 / sr)
    band = (freqs > 27) & (freqs < 2500)
    return freqs[band][np.argmax(hps[band])]

def name_midi(fname):
    m = re.search(r'_([A-G][#b]?)(-?\d)_', fname)
    if not m: return None
    return NAMES[m.group(1)], int(m.group(2))

def piano_midi(fname):
    m = re.search(r'_(\d{3})\.wav$', fname)
    return 21 + 2 * int(m.group(1)) if m else None

os.makedirs(DST, exist_ok=True)
index = []
report = []
for bank, (prefix, maxs, fade, rms_db) in BANKS.items():
    files = sorted(f for f in open(os.path.join(SRC, '_picked.txt')).read().splitlines() if f.startswith(prefix) and (bank != 'timphit' or '_Hit_' in f))
    for rel in files:
        sr, x = load(os.path.join(SRC, rel))
        # 앞 무음 제거(최대값의 -50dB 문턱)
        thr = np.abs(x).max() * 0.003
        start = int(np.argmax(np.abs(x) > thr))
        start = max(0, start - int(sr * 0.003))
        x = x[start:]
        x = x[: int(sr * maxs)]
        fl = min(len(x), int(sr * fade))
        x[-fl:] *= np.linspace(1, 0, fl) ** 2
        y = resample_poly(x, OUT_SR, sr).astype(np.float32)
        # 음높이: 파일 이름 후보(표기 관례 두 가지)와 검출값을 비교
        fname = os.path.basename(rel)
        if bank == 'piano':
            midi = piano_midi(fname)
        elif bank.startswith('timp'):
            est = f0(x, sr)
            midi = int(round(69 + 12 * np.log2(est / 440))) if est else 45
            report.append(f"{bank} {fname} -> {midi}")
        else:
            pc, octv = name_midi(fname)
            # 표기 관례: 하프는 과학적 표기(C4=60), 현악·목관·금관 묶음은 한 옥타브 낮춰 적는 관례(C3=60).
            # 검출값과 대조해 확인했다(저음은 배음이 더 크게 잡히는 경우가 있어 이름 관례를 우선한다).
            midi = 12 * (octv + 1) + pc if bank == 'harp' else 12 * (octv + 2) + pc
            est = f0(x, sr)
            report.append(f"{bank} {fname} -> {midi} est={est and round(69+12*np.log2(est/440),1)}")
        # 음량 맞춤(앞 1초 RMS 기준), 최대값 제한
        head = y[: OUT_SR]
        rms = np.sqrt(np.mean(head ** 2)) + 1e-9
        y *= (10 ** (rms_db / 20)) / rms
        peak = np.abs(y).max()
        if peak > 0.97: y *= 0.97 / peak
        out = f"{bank}_{midi}.pcm"
        # 같은 이름(드물게 중복)은 첫 것만
        if any(i[2] == out for i in index): continue
        (np.clip(y, -1, 1) * 32767).astype('<i2').tofile(os.path.join(DST, out))
        index.append((bank, midi, out, len(y)))

with open(os.path.join(DST, 'index.txt'), 'w') as f:
    f.write("# bank midi file frames  (22050 Hz, mono, 16-bit LE). Source: VSCO 2 Community Edition (CC0), Upright Piano by Simon Dalzell / Ivy Audio\n")
    for b, m, o, n in sorted(index):
        f.write(f"{b} {m} {o} {n}\n")
print("\n".join(report))
total = sum(os.path.getsize(os.path.join(DST, o)) for _, _, o, _ in index)
print("files", len(index), "bytes", total)
