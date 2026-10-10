#!/usr/bin/env python3
"""Build the shipped A2-B1 song deck and real-audio excerpts from a local video."""
import argparse
import html
import json
from pathlib import Path
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
BASE = 'https://srs.submergedstructure.com/'

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('video', type=Path)
    args = parser.parse_args()
    if not args.video.is_file():
        parser.error('Source video does not exist')
    content = json.loads((ROOT / 'content/geeks-english.json').read_text())
    directory = ROOT / 'docs/audio/geeks-english'
    directory.mkdir(parents=True, exist_ok=True)
    phrases = {p['key']: p for p in content['phrases']}
    for p in phrases.values():
        duration = p['end'] - p['start']
        subprocess.run(['ffmpeg', '-y', '-hide_banner', '-loglevel', 'error',
                        '-ss', str(p['start']), '-i', str(args.video), '-t', str(duration),
                        '-vn', '-af', f'afade=t=in:d=0.015,afade=t=out:st={duration-0.02}:d=0.02',
                        '-c:a', 'libmp3lame', '-q:a', '2',
                        str(directory / (p['key'] + '.mp3'))], check=True)
    cards = []
    for kind, entries in [('phrase', content['phrases']), ('word', content['words'])]:
        for item in entries:
            phrase = item if kind == 'phrase' else phrases[item['phrase']]
            english = html.escape(phrase['english'])
            if kind == 'word':
                highlight = html.escape(item['highlight'])
                assert english.count(highlight) == 1, item['key']
                english = english.replace(highlight, '<mark>'+highlight+'</mark>')
            prompt = 'Jak rozumiesz ten zwrot w piosence?' if kind == 'phrase' else 'Co znaczy wyróżnione słowo w tym kontekście?'
            audio = BASE + 'audio/geeks-english/' + phrase['key'] + '.mp3'
            front = '<p class="card-prompt">'+prompt+'</p><p class="lyric-phrase" lang="en">'+english+'</p><audio src="'+audio+'" preload="none"></audio>'
            notes = ''
            if kind == 'word':
                notes += '<p class="lyric-phrase" lang="en"><strong>English definition:</strong> '+html.escape(item['definition'])+'</p>'
            notes += '<p lang="pl"><strong>O tłumaczeniu:</strong> '+html.escape(item['translation_note'])+'</p>'
            cards.append({'id': str(uuid.uuid5(uuid.NAMESPACE_URL, BASE+'decks/geeks-english/'+kind+'/'+item['key'])),
                          'deck': content['deck_name'], 'front': front,
                          'back': '<p class="card-translation" lang="pl">'+html.escape(item['polish'])+'</p>', 'notes': notes})
    backup = {'version': 2, 'scheduling_data': False, 'deck_name': content['deck_name'], 'cards': cards}
    target = ROOT / 'docs/decks/geeks-english.json'
    target.write_text(json.dumps(backup, ensure_ascii=False, indent=2)+'\n')
    print(f'Built {len(content["phrases"])} phrase cards, {len(content["words"])} vocabulary cards and {len(phrases)} song clips: {target}')

if __name__ == '__main__':
    main()
