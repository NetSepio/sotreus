"""Exercise the actual migration SQL against SQLite and the exported Room schemas."""
import json
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / 'core/database/schemas/app.sotreus.core.database.SotreusDatabase'


class MigrationSixTest(unittest.TestCase):
    def test_upgrade_preserves_user_data_and_matches_room_schema(self):
        before = json.loads((SCHEMAS / '5.json').read_text())['database']
        after = json.loads((SCHEMAS / '6.json').read_text())['database']
        db = sqlite3.connect(':memory:')
        for table in before['entities']:
            db.execute(table['createSql'].replace('${TABLE_NAME}', table['tableName']))
        table = next(t for t in before['entities'] if t['tableName'] == 'entities')
        row = {f['columnName']: (0 if f['affinity'] in ('INTEGER', 'REAL') else '') if f.get('notNull') else None for f in table['fields']}
        row.update(id='private-wifi', radio='WIFI', address='02:11:22:33:44:55', address_type='PUBLIC',
                   link_confidence='HIGH', vendor='Inferred vendor', user_name='Keep label', note='Keep note',
                   user_state='TAGGED', guess_confidence='LOW')
        db.execute('INSERT INTO entities (' + ','.join(row) + ') VALUES (' + ','.join('?' for _ in row) + ')', list(row.values()))
        db.execute("INSERT INTO sessions(kind,name,started_at_ms,geotag,place_id,start_label) VALUES ('JOURNEY','Keep journey',1,0,NULL,NULL)")
        source = (ROOT / 'core/database/src/main/kotlin/app/sotreus/core/database/Migrations.kt').read_text()
        migration = source.split('val MIGRATION_5_6')[1].split('val MIGRATION_6_7')[0]
        sql = re.findall(r'db.execSQL\("(.*?)"\)', migration, re.S)
        self.assertEqual(3, len(sql))
        for statement in sql:
            db.execute(statement)
        self.assertEqual(('Keep label', 'Keep note', 'TAGGED', 'LOW', None),
                         db.execute('SELECT user_name,note,user_state,link_confidence,vendor FROM entities').fetchone())
        self.assertEqual('Keep journey', db.execute('SELECT name FROM sessions').fetchone()[0])
        for table in after['entities']:
            columns = {r[1]: (r[2], bool(r[3])) for r in db.execute('PRAGMA table_info(' + table['tableName'] + ')')}
            expected = {f['columnName']: (f['affinity'], f.get('notNull', False)) for f in table['fields']}
            self.assertEqual(expected, columns, table['tableName'])
        db.close()


class MigrationSevenTest(unittest.TestCase):
    def test_upgrade_preserves_vendor_cache_and_matches_room_schema(self):
        before = json.loads((SCHEMAS / '6.json').read_text())['database']
        after = json.loads((SCHEMAS / '7.json').read_text())['database']
        db = sqlite3.connect(':memory:')
        for table in before['entities']:
            db.execute(table['createSql'].replace('${TABLE_NAME}', table['tableName']))
        db.execute("INSERT INTO vendor_lookups VALUES ('BC071D','Saved vendor','MA-L','2024-12-10',1,99999)")
        db.execute("INSERT INTO session_entities VALUES (1,'ble:stable',1,2,1,-50,1,0)")
        source = (ROOT / 'core/database/src/main/kotlin/app/sotreus/core/database/Migrations.kt').read_text()
        migration = source.split('val MIGRATION_6_7')[1].split('val ALL')[0]
        sql = re.findall(r'db.execSQL\("(.*?)"\)', migration, re.S)
        self.assertEqual(12, len(sql))
        for statement in sql:
            db.execute(statement)
        self.assertEqual('Saved vendor', db.execute('SELECT vendor FROM vendor_lookups').fetchone()[0])
        self.assertEqual(0, db.execute('SELECT is_flood FROM session_entities').fetchone()[0])
        for table in after['entities']:
            columns = {r[1]: (r[2], bool(r[3])) for r in db.execute('PRAGMA table_info(' + table['tableName'] + ')')}
            expected = {f['columnName']: (f['affinity'], f.get('notNull', False)) for f in table['fields']}
            self.assertEqual(expected, columns, table['tableName'])
        db.close()


if __name__ == '__main__':
    unittest.main()
