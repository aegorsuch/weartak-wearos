from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET


RES = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "res"


class NearbyLocaleTests(unittest.TestCase):
    def test_review_and_consent_strings_cover_existing_locales(self):
        base = {s.attrib["name"]: s.text for s in ET.parse(RES / "values" / "strings.xml").getroot()
                if s.tag == "string" and s.attrib["name"].startswith(("import_", "export_", "package_"))}
        locales = [p for p in RES.glob("values-*")
                   if re.fullmatch(r"values-[a-z]{2}", p.name) and (p / "strings.xml").is_file()]
        self.assertEqual(9, len(locales))
        for locale in locales:
            with self.subTest(locale=locale.name):
                translated = {s.attrib["name"]: s.text
                              for s in ET.parse(locale / "nearby.xml").getroot()}
                self.assertEqual(base.keys(), translated.keys())
                for key, value in translated.items():
                    self.assertTrue(value.strip(), key)
                    self.assertEqual(re.findall(r"%\d+\$[sd]", base[key]),
                                     re.findall(r"%\d+\$[sd]", value), key)


if __name__ == "__main__":
    unittest.main()
