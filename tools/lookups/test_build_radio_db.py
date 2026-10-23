import json
import struct
import unittest
from datetime import date
from build_radio_db import pack, counts, read_ieee, tables_from_sources


class DatabaseBuildTest(unittest.TestCase):
    def test_longest_prefix_tables_and_utf8_survive_packing(self):
        tables = {tag: {} for tag in ('mal', 'cid', 'long', 'btc', 'app', 'uuid')}
        tables['mal'][0x001122] = 'Vendor'
        tables['long'][(36 << 56) | 0x001122334] = 'Éxample'
        tables['btc'][76] = 'Example'
        blob = pack(tables, date(2026, 10, 23))
        self.assertEqual({'mal': 1, 'cid': 0, 'long': 1, 'btc': 1, 'app': 0, 'uuid': 0}, counts(blob))
        self.assertIn('Éxample'.encode(), blob)
        self.assertEqual(20261023, struct.unpack_from('<I', blob, 8)[0])
        self.assertEqual(blob, pack(tables, date(2026, 10, 23)))

    def test_rejects_wrong_prefix_width(self):
        with self.assertRaises(ValueError):
            read_ieee(b'Registry,Assignment,Organization Name\nMA-L,00112,X\n', 6)

    def test_preserves_shared_historical_assignments(self):
        rows = b'Registry,Assignment,Organization Name\nMA-L,001122,Y\nMA-L,001122,X\n'
        self.assertEqual({0x001122: 'X / Y'}, read_ieee(rows, 6))

    def test_appearance_and_service_layout(self):
        raw = {name: b'Registry,Assignment,Organization Name\n' for name in ('mal.csv', 'mam.csv', 'mas.csv', 'iab.csv', 'cid.csv')}
        raw['company_ids.json'] = b'[{"code":76,"name":"Example"}]'
        raw['gap_appearance.json'] = b'[{"category":1,"name":"Phone","subcategory":[{"value":1,"name":"Handset"}]}]'
        raw['service_uuids.json'] = b'[{"uuid":"1800","name":"Access"},{"uuid":"12345678-1234-1234-1234-123456789ABC","name":"Custom"}]'
        tables = tables_from_sources(raw)
        self.assertEqual('Phone: Handset', tables['app'][65])
        self.assertEqual({0x1800:'Access'}, tables['uuid'])


if __name__ == '__main__':
    unittest.main()
