"""Adapt Fieldwatch's community packs to Sotreus resource keys; retain explicit English fallbacks."""
import argparse
import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET

TOKENS = re.compile(r'%\d+\$[sdif]|%[sdif]')
NAMED = re.compile(r'\{[A-Za-z][A-Za-z0-9_]*\}')

def canon(text):
    return re.sub(r'\s+', ' ', NAMED.sub('{}', TOKENS.sub('{}', text))).strip().casefold()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--upstream', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    english = {}
    files = sorted(root.glob('**/src/main/res/values/*.xml'), key=lambda p: (p.parts[0] == 'app', str(p)))
    for path in files:
        for node in ET.parse(path).getroot():
            if node.get('translatable') == 'false': continue
            name = node.get('name')
            def text(el): return ''.join(el.itertext()).replace("\\'", "'").replace('\\n', '\n').replace('\\"', '"')
            if node.tag == 'string': english[name] = text(node)
            elif node.tag == 'plurals':
                for item in node: english[name + '#' + item.get('quantity')] = text(item)
    domain = args.upstream / 'app/src/main/java/app/fieldwatch/domain'
    originals = {}
    pattern = re.compile(r'en\("([^"]+)",\s*("(?:\\.|[^"\\])*")')
    for filename in ['LanguageCopy.kt', 'ChromeLines.kt']:
        for key, literal in pattern.findall((domain / filename).read_text()):
            try: value = json.loads(literal.replace("\\'", "'").replace('\\$', '$'))
            except json.JSONDecodeError: continue
            originals.setdefault(canon(value), (key, value))
    destination = root / 'core/data/src/main/assets/languages'
    destination.mkdir(parents=True, exist_ok=True)
    def save(tag, name, strings):
        (destination / (tag + '.json')).write_text(json.dumps({'format': 'sotreus-language', 'formatVersion': 1, 'language': name,
            'languageTag': 'en' if tag == 'english' else tag, 'writtenFor': '1.1.1', 'strings': strings}, ensure_ascii=False, indent=2) + '\n')
    save('english', 'English', english)
    counts = []
    for path in sorted((args.upstream / 'app/src/main/assets/lang').glob('*.json')):
        pack = json.loads(path.read_text())
        translated = {}
        for key, value in english.items():
            hit = originals.get(canon(value))
            if not hit: continue
            fw_key, fw_english = hit
            target = pack['strings'].get(fw_key)
            if not target: continue
            native_tokens = TOKENS.findall(value)
            fw_tokens = NAMED.findall(fw_english)
            if len(native_tokens) != len(fw_tokens): continue
            for old, new in zip(fw_tokens, native_tokens): target = target.replace(old, new)
            if NAMED.search(target) or sorted(TOKENS.findall(target)) != sorted(native_tokens): continue
            translated[key] = target
        save(path.stem, pack['language'], translated)
        counts.append(len(translated))
    print(f'{len(counts)} community packs; {len(english)} English template entries; {min(counts)}–{max(counts)} adapted entries per pack')

if __name__ == '__main__': main()
