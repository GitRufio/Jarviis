#!/usr/bin/env python3
"""Głosowa pętla Jarvisa (100% lokalnie, język polski).

Cykl: ciągłe nasłuchiwanie "hey Jarvis" -> sygnał -> nagranie komendy ->
transkrypcja (faster-whisper) -> POST /v1/tasks -> odpowiedź na głos (Piper).

Użycie:
  voice/.venv/bin/python voice/jarvis_voice.py            # pętla głosowa
  voice/.venv/bin/python voice/jarvis_voice.py --list-devices
  voice/.venv/bin/python voice/jarvis_voice.py --test-mic  # 5 s nagrania + transkrypcja
  voice/.venv/bin/python voice/jarvis_voice.py --test-wake  # na żywo: wyniki "hey Jarvis", dobierz próg
  voice/.venv/bin/python voice/jarvis_voice.py --check-mic  # kreator: które wejście słyszy Twój głos
  voice/.venv/bin/python voice/jarvis_voice.py --say "Cześć"

Zmienne środowiskowe: JARVIS_MIC (fragment nazwy lub indeks; domyślnie "LifeCam"
= mikrofon z kamerki USB), JARVIS_API (domyślnie http://localhost:8080),
JARVIS_WHISPER_MODEL (domyślnie small), JARVIS_WAKE_THRESHOLD (domyślnie 0.5).
"""

import argparse
import os
import queue
import re
import shutil
import subprocess
import sys
import tempfile
import time

import numpy as np

RATE_16K = 16000
FRAME_SEC = 0.08  # ramka 80 ms dla openWakeWord
SILENCE_SEC = 1.2
MAX_CMD_SEC = 20.0

HERE = os.path.dirname(os.path.abspath(__file__))
VOICE_MODEL = os.path.join(HERE, "models", "pl_PL-darkman-medium.onnx")
WAKE_KEY = "hey_jarvis_v0.1"


def wake_model_path():
    import openwakeword

    return os.path.join(
        os.path.dirname(os.path.abspath(openwakeword.__file__)),
        "resources", "models", "hey_jarvis_v0.1.onnx")


def log(*parts):
    print("[jarvis-voice]", *parts, flush=True)


def resolve_mic(spec):
    import sounddevice as sd

    devices = sd.query_devices()
    if spec.isdigit():
        idx = int(spec)
        if devices[idx]["max_input_channels"] <= 0:
            raise SystemExit(f"Urządzenie {idx} nie ma wejścia.")
        return idx, devices[idx]["name"]
    needle = spec.lower()
    for i, dev in enumerate(devices):
        if dev["max_input_channels"] > 0 and needle in dev["name"].lower():
            return i, dev["name"]
    raise SystemExit(f"Nie znaleziono mikrofonu pasującego do {spec!r}.")


def play_wav(path, player=None):
    player = player or shutil.which("aplay") or shutil.which("paplay")
    if not player:
        raise SystemExit("Brak odtwarzacza (aplay/paplay).")
    cmd = [player, path] if "aplay" in player else [player, path]
    if "aplay" in player:
        cmd = ["aplay", "-D", "pulse", path]
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def beep(player=None):
    sr = 22050
    t = np.arange(int(sr * 0.14)) / sr
    tone = (0.4 * np.sin(2 * np.pi * 880 * t) * np.hanning(len(t))).astype(np.float32)
    path = os.path.join(tempfile.gettempdir(), "jarvis_beep.wav")
    write_wav(path, tone, sr)
    play_wav(path, player)


def write_wav(path, mono_float32, sr):
    import wave

    pcm = np.clip(mono_float32, -1.0, 1.0)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes((pcm * 32767).astype(np.int16).tobytes())


def cleanup_for_speech(text):
    text = re.sub(r"```.*?```", " Kod pokazuję w logach. ", text, flags=re.DOTALL)
    text = re.sub(r"`([^`]*)`", r"\1", text)
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"^#{1,6}\s*", "", text, flags=re.MULTILINE)
    text = re.sub(r"\s+", " ", text).strip()
    if len(text) > 900:
        text = text[:900].rsplit(" ", 1)[0] + "…"
    return text or "Nie mam odpowiedzi."


def speak(text, venv_bin, player=None):
    import wave

    cleaned = cleanup_for_speech(text)
    piper = os.path.join(venv_bin, "piper")
    if not os.path.exists(piper):
        piper = shutil.which("piper") or piper
    out = os.path.join(tempfile.gettempdir(), "jarvis_say.wav")
    proc = subprocess.run(
        [piper, "--model", VOICE_MODEL, "--output_file", out],
        input=cleaned.encode("utf-8"),
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
        timeout=120,
    )
    if proc.returncode != 0 or not os.path.exists(out):
        log("Piper błąd:", proc.stderr.decode("utf-8", "replace")[-300:])
        return
    with wave.open(out, "rb") as w:
        if w.getnframes() == 0:
            log("Piper zwrócił pusty dźwięk.")
            return
    play_wav(out, player)


class MicStream:
    """Ciągły strumień z mikrofonu + resampling do 16 kHz, ramki 80 ms."""

    def __init__(self, device_idx):
        import sounddevice as sd
        from scipy.signal import resample_poly

        self._sd = sd
        self._resample = resample_poly
        info = sd.query_devices(device_idx)
        self.native_rate = int(info.get("default_samplerate") or 44100)
        if self.native_rate not in (44100, 48000, 32000, 22050, 16000):
            self.native_rate = 44100
        self.block = int(self.native_rate * FRAME_SEC)
        self.queue: queue.Queue = queue.Queue()
        self.stream = sd.InputStream(
            device=device_idx,
            samplerate=self.native_rate,
            channels=1,
            dtype="float32",
            blocksize=self.block,
            callback=self._cb,
        )

    def _cb(self, indata, frames, time_info, status):
        try:
            self.queue.put_nowait(indata[:, 0].copy())
        except queue.Full:
            pass

    def __enter__(self):
        self.stream.start()
        return self

    def __exit__(self, *exc):
        self.stream.stop()
        self.stream.close()

    def frame_16k(self, timeout=5.0):
        """Jedna ramka 80 ms jako float32 @16kHz (1280 próbek)."""
        chunk = self.queue.get(timeout=timeout)
        if self.native_rate == RATE_16K:
            out = chunk
        else:
            out = self._resample(chunk, RATE_16K, self.native_rate).astype(np.float32)
        if len(out) >= 1280:
            return out[:1280]
        return np.pad(out, (0, 1280 - len(out)))

    def drain(self):
        while not self.queue.empty():
            try:
                self.queue.get_nowait()
            except queue.Empty:
                break


def calibrate(mic, seconds=1.0):
    frames = []
    deadline = time.time() + seconds
    while time.time() < deadline:
        frames.append(mic.frame_16k())
    rms = float(np.sqrt(np.mean(np.concatenate(frames) ** 2)))
    threshold = max(0.02, rms * 3.0)
    log(f"Szum tła RMS={rms:.4f}, próg mowy={threshold:.4f}")
    return threshold


def record_command(mic, threshold):
    """Nagrywa do ciszy (1,2 s) lub max 20 s. Zwraca audio float32 @16kHz."""
    pre_roll, speech, silent = [], [], 0
    started, deadline = False, time.time() + MAX_CMD_SEC
    silent_needed = int(SILENCE_SEC / FRAME_SEC)
    while time.time() < deadline:
        frame = mic.frame_16k(timeout=5.0)
        rms = float(np.sqrt(np.mean(frame**2)))
        if not started:
            pre_roll.append(frame)
            if len(pre_roll) > 6:
                pre_roll.pop(0)
            if rms > threshold:
                started = True
                speech.extend(pre_roll)
                speech.append(frame)
        else:
            speech.append(frame)
            silent = silent + 1 if rms < threshold else 0
            if silent >= silent_needed:
                break
    if not started:
        return None
    return np.concatenate(speech)


def transcribe(model, audio):
    # condition_on_previous_text=False odcina dopowiadanie/halucynacje,
    # repetition_penalty hamuje zapętlenia - kluczowe dla polskiego z mikrofonu.
    segments, _ = model.transcribe(
        audio, language="pl", beam_size=5, vad_filter=True,
        vad_parameters=dict(min_silence_duration_ms=500),
        condition_on_previous_text=False,
        compression_ratio_threshold=2.4,
        no_speech_threshold=0.6,
        repetition_penalty=1.1,
        no_repeat_ngram_size=3,
    )
    return "".join(seg.text for seg in segments).strip()


def ask_jarvis(api, text):
    import requests

    resp = requests.post(api.rstrip("/") + "/v1/tasks", json={"input": text}, timeout=180)
    resp.raise_for_status()
    return resp.json().get("result") or "Nie mam odpowiedzi."


def cmd_list_devices(_args):
    import sounddevice as sd

    for i, dev in enumerate(sd.query_devices()):
        if dev["max_input_channels"] > 0:
            print(f"{i}: {dev['name']} (wejść: {dev['max_input_channels']})")


def cmd_test_wake(args):
    from openwakeword import Model as WakeModel

    idx, name = resolve_mic(args.mic)
    wake = WakeModel(wakeword_model_paths=[wake_model_path()])
    log(f"Mikrofon: {idx} ({name}). Mów 'hej Jarvis' - Ctrl+C kończy.")
    with MicStream(idx) as mic:
        while True:
            frame = mic.frame_16k(timeout=10.0)
            pcm16 = (np.clip(frame, -1.0, 1.0) * 32767).astype(np.int16)
            score = float(wake.predict(pcm16).get(WAKE_KEY, 0.0))
            bar = "#" * int(score * 30)
            flag = "  <-- WYBUDZENIE" if score >= args.wake_threshold else ""
            print(f"\r{score:.2f} [{bar:<30}]{flag}   ", end="", flush=True)


def cmd_test_mic(args):
    idx, name = resolve_mic(args.mic)
    log(f"Mikrofon: {idx} ({name}). Mów przez 5 s...")
    with MicStream(idx) as mic:
        audio = []
        deadline = time.time() + 5.0
        while time.time() < deadline:
            audio.append(mic.frame_16k())
    audio = np.concatenate(audio)
    log(f"Nagrano {len(audio) / RATE_16K:.1f} s, RMS={float(np.sqrt(np.mean(audio**2))):.4f}")
    from faster_whisper import WhisperModel

    model = WhisperModel(args.whisper_model, device="cpu", compute_type="int8")
    text = transcribe(model, audio)
    print("TRANSKRYPCJA:", text or "(pusto)")


def cmd_check_mic(args):
    """Kreator diagnostyczny: nagrywa z każdego wejścia analogowego z odliczaniem,
    raportuje poziomy, transkrypcję i wynik wake-word. Mów, gdy każe."""
    import sounddevice as sd
    from faster_whisper import WhisperModel
    from openwakeword import Model as WakeModel

    candidates = []
    for i, dev in enumerate(sd.query_devices()):
        if dev["max_input_channels"] > 0 and ("ALC897" in dev["name"] or "LifeCam" in dev["name"]):
            candidates.append((i, dev["name"]))
    if not candidates:
        raise SystemExit("Brak urządzeń wejściowych.")

    log("Ładowanie modeli (chwilę to potrwa)...")
    stt = WhisperModel(args.whisper_model, device="cpu", compute_type="int8")
    wake = WakeModel(wakeword_model_paths=[wake_model_path()])

    results = []
    for idx, name in candidates:
        print(f"\n=== Wejście {idx}: {name} ===")
        print("Za 3 sekundy nagrywam 5 s - wtedy MÓW GŁOŚNO: 'hej Jarvis, test mikrofonu'.")
        for n in (3, 2, 1):
            print(f"  {n}...", flush=True)
            time.sleep(1)
        print("  NAGRYWAM - MÓW!", flush=True)
        with MicStream(idx) as mic:
            audio = []
            deadline = time.time() + 5.0
            while time.time() < deadline:
                audio.append(mic.frame_16k())
        audio = np.concatenate(audio)
        peak = int(np.max(np.abs(audio)) * 32767)
        rms = float(np.sqrt(np.mean(audio**2)))
        text = transcribe(stt, audio)
        best = 0.0
        for i in range(0, len(audio) - 1280, 1280):
            frame = (np.clip(audio[i:i + 1280], -1, 1) * 32767).astype(np.int16)
            best = max(best, float(wake.predict(frame).get(WAKE_KEY, 0.0)))
        heard = peak > 500 and len(text) >= 2
        results.append((idx, name, heard))
        print(f"  poziom: peak={peak} RMS={rms:.4f} | transkrypcja: {text or '(pusto)'}")
        print(f"  wake-word max: {best:.2f} | werdykt: {'SŁYSZY CIĘ' if heard else 'nie słyszy'}")

    print("\n=== Podsumowanie ===")
    good = [r for r in results if r[2]]
    if good:
        idx, name, _ = good[0]
        print(f"Używaj mikrofonu {idx} ({name}):")
        print(f"  voice/.venv/bin/python voice/jarvis_voice.py --mic {idx}")
        print(f"  lub: export JARVIS_MIC={idx}")
    else:
        print("Żadne wejście nie usłyszało mowy. Sprawdź:")
        print("  1. czy wtyczka mikrofonu jest w gnieździe (przód/tył obudowy),")
        print("  2. przycisk wyciszenia na mikrofonie/kablu,")
        print("  3. suwak wejścia w ustawieniach dźwięku systemu.")


def cmd_say(args):
    speak(args.say, args.venv_bin, args.player)
    log("OK")


def cmd_loop(args):
    from faster_whisper import WhisperModel
    from openwakeword import Model as WakeModel

    idx, name = resolve_mic(args.mic)
    log(f"Mikrofon: {idx} ({name})")
    log("Ładowanie modelu STT...")
    stt = WhisperModel(args.whisper_model, device="cpu", compute_type="int8")
    log("Ładowanie modelu wake-word (hey Jarvis)...")
    wake = WakeModel(wakeword_model_paths=[wake_model_path()])
    log(f"Gotowy. Powiedz: hej Jarvis. Próg: {args.wake_threshold}")

    with MicStream(idx) as mic:
        calibrate(mic)
        while True:
            frame = mic.frame_16k(timeout=10.0)
            pcm16 = np.clip(frame, -1.0, 1.0)
            score = float(wake.predict((pcm16 * 32767).astype(np.int16)).get(WAKE_KEY, 0.0))
            if score < args.wake_threshold:
                continue
            log(f"Wykryto 'hey Jarvis' ({score:.2f}). Słucham...")
            mic.drain()
            beep(args.player)
            audio = record_command(mic, calibrate(mic, seconds=0.4))
            if audio is None or len(audio) < RATE_16K // 3:
                log("Cisza - wracam do nasłuchu.")
                mic.drain()
                continue
            text = transcribe(stt, audio)
            log("Usłyszano:", text or "(pusto)")
            if len(text) < 2:
                speak("Nie dosłyszałem, powtórz proszę.", args.venv_bin, args.player)
                mic.drain()
                continue
            try:
                answer = ask_jarvis(args.api, text)
            except Exception as exc:  # noqa: BLE001 - komunikat głosowy zamiast stack trace
                log("Błąd API:", exc)
                answer = "Nie mogę połączyć się z Jarvisem. Sprawdź czy serwer działa."
            log("Jarvis:", answer[:200])
            speak(answer, args.venv_bin, args.player)
            mic.drain()
            time.sleep(0.5)


def main():
    parser = argparse.ArgumentParser(description="Głosowy Jarvis (PL, lokalnie).")
    parser.add_argument("--mic", default=os.getenv("JARVIS_MIC", "LifeCam"))
    parser.add_argument("--api", default=os.getenv("JARVIS_API", "http://localhost:8080"))
    parser.add_argument("--whisper-model", default=os.getenv("JARVIS_WHISPER_MODEL", "large-v3-turbo"))
    parser.add_argument("--wake-threshold", type=float, default=float(os.getenv("JARVIS_WAKE_THRESHOLD", "0.35")))
    parser.add_argument("--venv-bin", default=os.path.join(HERE, ".venv", "bin"))
    parser.add_argument("--player", default=None)
    parser.add_argument("--list-devices", action="store_true")
    parser.add_argument("--test-mic", action="store_true")
    parser.add_argument("--test-wake", action="store_true")
    parser.add_argument("--check-mic", action="store_true")
    parser.add_argument("--say", default=None)
    args = parser.parse_args()

    if not os.path.exists(VOICE_MODEL):
        raise SystemExit(f"Brak modelu głosu: {VOICE_MODEL}")
    try:
        if args.list_devices:
            return cmd_list_devices(args)
        if args.test_mic:
            return cmd_test_mic(args)
        if args.test_wake:
            return cmd_test_wake(args)
        if args.check_mic:
            return cmd_check_mic(args)
        if args.say:
            return cmd_say(args)
        return cmd_loop(args)
    except KeyboardInterrupt:
        log("Koniec.")
        return None


if __name__ == "__main__":
    main()
