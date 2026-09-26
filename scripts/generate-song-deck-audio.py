#!/usr/bin/env python3
"""Generate card-front audio from an export of all four song decks.

Run with: api-shell python3 scripts/generate-song-deck-audio.py BACKUP.json
"""

import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "docs/audio"
MANIFEST = OUTPUT / "song-decks.js"
DECK_VOICES = {
    "Noc Komety": ("noc-komety", "aAY9hMI6VU335JUszdRs"),  # Aleksandra
    "Takie tango": ("takie-tango", "B9cNwbQXN3s6l3nU6fqz"),  # Adam
    "Wszystko kwitnie wkoło": ("wszystko-kwitnie-wkolo", "C8ZVSJxcymeT86xT429O"),  # Luiza
    "Zacznij od Bacha": ("zacznij-od-bacha", "Tq9w09mAjuFKRAZRcBR5"),  # Marek
}
ENGLISH_VOICE = "JBFqnCBsd6RMkjVDRZzb"  # George


def synthesize(text, voice_id, destination):
    url = f"https://api.elevenlabs.io/v1/text-to-speech/{voice_id}?output_format=mp3_44100_128"
    body = json.dumps({"text": text, "model_id": "eleven_multilingual_v2"}).encode()
    request = urllib.request.Request(
        url,
        data=body,
        headers={
            "xi-api-key": os.environ["ELEVENLABS_API_KEY"],
            "Content-Type": "application/json",
        },
    )
    for attempt in range(5):
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                audio = response.read()
            if not audio.startswith(b"ID3") and audio[:2] not in (b"\xff\xfb", b"\xff\xf3"):
                raise ValueError(f"Unexpected audio response for {text!r}")
            destination.write_bytes(audio)
            return
        except urllib.error.HTTPError as error:
            if error.code not in (429, 500, 502, 503, 504) or attempt == 4:
                raise RuntimeError(f"ElevenLabs returned HTTP {error.code} for {text!r}") from error
            time.sleep(2 ** attempt)


def main():
    backup = json.loads(Path(sys.argv[1]).read_text())
    cards = backup["cards"]
    decks = {card["deck"] for card in cards}
    if decks != set(DECK_VOICES):
        raise ValueError(f"Expected all four song decks, got {sorted(decks)}")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    mapping = {}
    for deck, (slug, voice) in DECK_VOICES.items():
        fronts = [card["front"] for card in cards if card["deck"] == deck]
        if len(fronts) != len(set(fronts)):
            raise ValueError(f"Duplicate fronts in {deck} need distinct audio filenames")
        directory = OUTPUT / slug
        directory.mkdir(exist_ok=True)
        mapping[deck] = {}
        for index, front in enumerate(fronts, 1):
            filename = front + ".mp3"
            destination = directory / filename
            if not destination.exists():
                selected_voice = ENGLISH_VOICE if front == "Which FSRS version schedules these cards?" else voice
                synthesize(front, selected_voice, destination)
            mapping[deck][front] = "audio/" + slug + "/" + urllib.parse.quote(filename)
            print(f"{deck}: {index}/{len(fronts)} {front}", flush=True)
    MANIFEST.write_text("window.SRS_CARD_AUDIO = " + json.dumps(mapping, ensure_ascii=False, indent=2) + ";\n")


if __name__ == "__main__":
    main()
