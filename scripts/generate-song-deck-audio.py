#!/usr/bin/env python3
"""Generate card-front audio and Noc Komety phrase audio from a card export.

Run with: api-shell python3 scripts/generate-song-deck-audio.py BACKUP.json
"""

import json
import hashlib
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from html.parser import HTMLParser


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


class SongPhraseParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.phrase_depth = 0
        self.token_depth = 0
        self.sup_depth = 0
        self.token = []
        self.tokens = []
        self.phrases = []

    def handle_starttag(self, tag, attrs):
        classes = dict(attrs).get("class", "").split()
        if self.phrase_depth:
            self.phrase_depth += 1
        elif "grammar-phrase" in classes:
            self.phrase_depth = 1
            self.tokens = []
        if self.token_depth:
            self.token_depth += 1
        elif self.phrase_depth and "grammar-token" in classes:
            self.token_depth = 1
            self.token = []
        if self.token_depth and tag == "sup":
            self.sup_depth += 1

    def handle_endtag(self, tag):
        if self.token_depth and tag == "sup":
            self.sup_depth -= 1
        if self.token_depth:
            self.token_depth -= 1
            if not self.token_depth:
                text = " ".join("".join(self.token).replace("!!", "").split())
                if text:
                    self.tokens.append(text)
        if self.phrase_depth:
            self.phrase_depth -= 1
            if not self.phrase_depth and self.tokens:
                self.phrases.append(" ".join(self.tokens))

    def handle_data(self, data):
        if self.token_depth and not self.sup_depth:
            self.token.append(data)


def song_phrases(back):
    parser = SongPhraseParser()
    parser.feed(back)
    return parser.phrases


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
    back_mapping = {"Noc Komety": {}}
    phrase_directory = OUTPUT / "noc-komety-phrases"
    phrase_directory.mkdir(exist_ok=True)
    for card in cards:
        if card["deck"] != "Noc Komety":
            continue
        phrases = song_phrases(card["back"])
        if not phrases:
            continue
        text = "\n".join(phrases)
        filename = hashlib.sha256(text.encode()).hexdigest()[:16] + ".mp3"
        destination = phrase_directory / filename
        if not destination.exists():
            synthesize(text, DECK_VOICES["Noc Komety"][1], destination)
        back_mapping["Noc Komety"][card["front"]] = "audio/noc-komety-phrases/" + filename
        print(f"Noc Komety phrase: {card['front']} => {text!r}", flush=True)
    MANIFEST.write_text(
        "window.SRS_CARD_AUDIO = " + json.dumps(mapping, ensure_ascii=False, indent=2) + ";\n"
        "window.SRS_CARD_BACK_AUDIO = " + json.dumps(back_mapping, ensure_ascii=False, indent=2) + ";\n"
    )


if __name__ == "__main__":
    main()
