"""Validate shipped overlays independently of Android's resource fallback."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'core/data/src/main/assets/languages'
TOKENS = re.compile(r'%(?:[0-9]+\$)?[-+ 0,(]*[0-9]*(?:\.[0-9]+)?[a-zA-Z%]')


class LanguageAssetsTest(unittest.TestCase):
    def test_every_selectable_language_has_a_valid_partial_pack(self):
        source = (ROOT / 'intelligence/fieldwatch/src/main/kotlin/app/sotreus/intelligence/fieldwatch/LanguageCopy.kt').read_text()
        tags = re.findall(r'BundledLanguage\("([^"]+)"', source)
        english = json.loads((ASSETS / 'english.json').read_text())['strings']
        self.assertEqual(26, len(tags))
        self.assertGreater(len(english), 1_000)
        for tag in tags:
            pack = json.loads((ASSETS / f'{tag}.json').read_text())
            self.assertEqual(tag, pack['languageTag'])
            self.assertEqual('sotreus-language', pack['format'])
            self.assertEqual(1, pack['formatVersion'])
            self.assertGreater(len(pack['strings']), 50)
            for key, value in pack['strings'].items():
                self.assertIn(key, english)
                self.assertTrue(value.strip())
                self.assertLessEqual(len(value), 4_096)
                self.assertEqual(sorted(TOKENS.findall(english[key])), sorted(TOKENS.findall(value)), f'{tag}:{key}')


if __name__ == '__main__':
    unittest.main()
