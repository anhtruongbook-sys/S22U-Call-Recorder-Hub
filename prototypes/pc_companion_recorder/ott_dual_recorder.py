"""
Prototype: Ghi Âm Cuộc Gọi OTT 2 Chiều (Zalo PC, Viber, Telegram, WhatsApp Desktop)
-----------------------------------------------------------------------------------
Nguyên lý:
- Thu đồng thời 2 luồng âm thanh độc lập trên Windows thông qua WASAPI:
  1. Kênh Trái (Left Channel) : Microphone của người dùng (Uplink)
  2. Kênh Phải (Right Channel): WASAPI Loopback từ Loa/Tai nghe - Tiếng người bên kia nói (Downlink)
- Tự động xuất tệp WAV Stereo chuẩn 16-bit / 44.1kHz kèm mã băm toàn vẹn SHA-256.

Cài đặt thư viện:
    pip install PyAudioWPatch numpy
"""

import datetime
import hashlib
import json
import os
import sys
import threading
import time
import wave

try:
    import numpy as np
    import pyaudiowpatch as pyaudio
except ImportError:
    print("[!] Chưa cài đặt thư viện PyAudioWPatch hoặc numpy.")
    print("    Vui lòng chạy lệnh: pip install PyAudioWPatch numpy")
    sys.exit(1)


class DualChannelOTTRecorder:
    def __init__(self, sample_rate: int = 44100, chunk_size: int = 1024):
        self.sample_rate = sample_rate
        self.chunk_size = chunk_size
        self.p = pyaudio.PyAudio()
        self.is_recording = False
        self.mic_frames = []
        self.loopback_frames = []

    def get_default_devices(self):
        """Lấy thiết bị Microphone mặc định và thiết bị WASAPI Loopback (Loa/Tai nghe) mặc định."""
        wasapi_info = self.p.get_host_api_info_by_type(pyaudio.paWASAPI)

        # 1. Thiết bị Microphone mặc định
        default_mic = self.p.get_device_info_by_index(wasapi_info["defaultInputDevice"])

        # 2. Thiết bị Loa mặc định & tìm Loopback tương ứng
        default_speakers = self.p.get_device_info_by_index(wasapi_info["defaultOutputDevice"])
        loopback_device = None

        if not default_speakers["isLoopbackDevice"]:
            for loopback in self.p.get_loopback_device_info_generator():
                if default_speakers["name"] in loopback["name"]:
                    loopback_device = loopback
                    break
        else:
            loopback_device = default_speakers

        return default_mic, loopback_device

    def start_recording(self, output_dir: str = "recordings", call_tag: str = "Zalo_Telegram_Call"):
        os.makedirs(output_dir, exist_ok=True)
        mic_dev, loop_dev = self.get_default_devices()

        if not loop_dev:
            raise RuntimeError("Không tìm thấy thiết bị WASAPI Loopback trên hệ thống Windows.")

        print(f"[*] Kênh Mic (Bạn nói)       : {mic_dev['name']}")
        print(f"[*] Kênh Loopback (Bên kia)  : {loop_dev['name']}")
        print("[*] Đang ghi âm cuộc gọi 2 chiều... (Nhấn Ctrl+C để kết thúc và lưu tệp)")

        self.is_recording = True
        start_time = datetime.datetime.now()
        timestamp_str = start_time.strftime("%Y%m%d_%H%M%S")
        wav_filename = os.path.join(output_dir, f"{call_tag}_{timestamp_str}.wav")

        # Mở stream Microphone
        mic_rate = int(mic_dev["defaultSampleRate"])
        mic_channels = min(2, int(mic_dev["maxInputChannels"]))
        mic_stream = self.p.open(
            format=pyaudio.paInt16,
            channels=mic_channels,
            rate=mic_rate,
            input=True,
            input_device_index=mic_dev["index"],
            frames_per_buffer=self.chunk_size,
        )

        # Mở stream Loopback (Âm thanh phát ra từ Zalo/Viber/Telegram/WhatsApp)
        loop_rate = int(loop_dev["defaultSampleRate"])
        loop_channels = min(2, int(loop_dev["maxInputChannels"]))
        loop_stream = self.p.open(
            format=pyaudio.paInt16,
            channels=loop_channels,
            rate=loop_rate,
            input=True,
            input_device_index=loop_dev["index"],
            frames_per_buffer=self.chunk_size,
        )

        try:
            while self.is_recording:
                if mic_stream.get_read_available() >= self.chunk_size:
                    self.mic_frames.append(mic_stream.read(self.chunk_size, exception_on_overflow=False))
                if loop_stream.get_read_available() >= self.chunk_size:
                    self.loopback_frames.append(loop_stream.read(self.chunk_size, exception_on_overflow=False))
                time.sleep(0.002)
        except KeyboardInterrupt:
            print("\n[*] Đang dừng ghi âm và trộn kênh Stereo...")
        finally:
            self.is_recording = False
            mic_stream.stop_stream()
            mic_stream.close()
            loop_stream.stop_stream()
            loop_stream.close()
            self.p.terminate()

        self._save_stereo_wav(
            wav_filename,
            mic_rate=mic_rate,
            mic_channels=mic_channels,
            loop_rate=loop_rate,
            loop_channels=loop_channels,
            start_time=start_time,
        )

    def _save_stereo_wav(
        self,
        wav_filename: str,
        mic_rate: int,
        mic_channels: int,
        loop_rate: int,
        loop_channels: int,
        start_time: datetime.datetime,
    ):
        """Gộp kênh Mic (Left) và kênh Loopback (Right) thành file WAV Stereo đồng nhất."""
        mic_raw = b"".join(self.mic_frames)
        loop_raw = b"".join(self.loopback_frames)

        mic_np = np.frombuffer(mic_raw, dtype=np.int16)
        if mic_channels == 2 and len(mic_np) > 0:
            mic_np = mic_np.reshape(-1, 2).mean(axis=1).astype(np.int16)

        loop_np = np.frombuffer(loop_raw, dtype=np.int16)
        if loop_channels == 2 and len(loop_np) > 0:
            loop_np = loop_np.reshape(-1, 2).mean(axis=1).astype(np.int16)

        # Đưa về cùng độ dài theo kênh dài hơn
        target_rate = loop_rate
        if mic_rate != target_rate and len(mic_np) > 0:
            duration = len(mic_np) / mic_rate
            target_len = int(duration * target_rate)
            mic_np = np.interp(
                np.linspace(0, len(mic_np) - 1, target_len),
                np.arange(len(mic_np)),
                mic_np,
            ).astype(np.int16)

        max_len = max(len(mic_np), len(loop_np))
        if max_len == 0:
            print("[!] Không có dữ liệu âm thanh nào được ghi nhận.")
            return

        mic_padded = np.pad(mic_np, (0, max_len - len(mic_np)), mode="constant")
        loop_padded = np.pad(loop_np, (0, max_len - len(loop_np)), mode="constant")

        # Ghép thành Stereo: Kênh Trái = Mic, Kênh Phải = Người bên kia
        stereo_np = np.column_stack((mic_padded, loop_padded)).ravel()

        with wave.open(wav_filename, "wb") as wf:
            wf.setnchannels(2)
            wf.setsampwidth(2)  # 16-bit
            wf.setframerate(target_rate)
            wf.writeframes(stereo_np.tobytes())

        # Tính mã băm SHA-256 bảo toàn chứng cứ nguyên bản
        sha256_hash = hashlib.sha256()
        with open(wav_filename, "rb") as f:
            for chunk in iter(lambda: f.read(65536), b""):
                sha256_hash.update(chunk)
        digest = sha256_hash.hexdigest()

        meta_filename = wav_filename + ".meta.json"
        meta_data = {
            "file": os.path.basename(wav_filename),
            "recorded_at": start_time.isoformat(),
            "sample_rate_hz": target_rate,
            "channels": {
                "left": "Microphone (Local User)",
                "right": "WASAPI Loopback (Remote Caller - Zalo/Viber/Telegram/WhatsApp)",
            },
            "sha256_integrity": digest,
        }
        with open(meta_filename, "w", encoding="utf-8") as mf:
            json.dump(meta_data, mf, indent=2, ensure_ascii=False)

        print(f"[+] Đã lưu file ghi âm Stereo : {wav_filename}")
        print(f"[+] Mã toàn vẹn SHA-256       : {digest}")
        print(f"[+] File Metadata             : {meta_filename}")


if __name__ == "__main__":
    recorder = DualChannelOTTRecorder()
    recorder.start_recording()
