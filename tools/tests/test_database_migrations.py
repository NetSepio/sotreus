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
        migration = source.split('val MIGRATION_6_7')[1].split('val MIGRATION_7_8')[0]
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



def _migration_sql(name, next_marker):
    source = (ROOT / 'core/database/src/main/kotlin/app/sotreus/core/database/Migrations.kt').read_text()
    return re.findall(r'db.execSQL\("(.*?)"\)', source.split('val ' + name)[1].split(next_marker)[0], re.S)


def _create(version):
    schema = json.loads((SCHEMAS / f'{version}.json').read_text())['database']
    db = sqlite3.connect(':memory:')
    for table in schema['entities']:
        db.execute(table['createSql'].replace('${TABLE_NAME}', table['tableName']))
        for index in table.get('indices', []):
            db.execute(index['createSql'].replace('${TABLE_NAME}', table['tableName']))
    return db, schema


def _insert_minimal(db, schema, table_name, **values):
    table = next(t for t in schema['entities'] if t['tableName'] == table_name)
    row = {f['columnName']: (0 if f['affinity'] in ('INTEGER', 'REAL') else '') if f.get('notNull') else None for f in table['fields']}
    row.update(values)
    db.execute(f'INSERT INTO {table_name} (' + ','.join(row) + ') VALUES (' + ','.join('?' for _ in row) + ')', list(row.values()))


def _assert_matches(test, db, version):
    after = json.loads((SCHEMAS / f'{version}.json').read_text())['database']
    for table in after['entities']:
        columns = {r[1]: (r[2], bool(r[3])) for r in db.execute('PRAGMA table_info(' + table['tableName'] + ')')}
        expected = {f['columnName']: (f['affinity'], f.get('notNull', False)) for f in table['fields']}
        test.assertEqual(expected, columns, table['tableName'])
        indices = {r[1] for r in db.execute('PRAGMA index_list(' + table['tableName'] + ')') if not r[1].startswith('sqlite_autoindex')}
        test.assertEqual({i['name'] for i in table.get('indices', [])}, indices, table['tableName'])


class MigrationEightTest(unittest.TestCase):
    """v8 (Sotreus Edge): located entities and encounters, and the Edge tables."""

    def test_upgrade_keeps_entities_and_encounters_and_matches_room_schema(self):
        db, before = _create(7)
        _insert_minimal(db, before, 'entities', id='ble:keep', radio='BLE', address='AA:BB:CC:DD:EE:FF', user_name='Keep label', note='Keep note')
        _insert_minimal(db, before, 'encounters', id=1, entity_id='ble:keep', started_at_ms=1, ended_at_ms=2)
        sql = _migration_sql('MIGRATION_7_8', 'val MIGRATION_8_9')
        self.assertEqual(24, len(sql))
        for statement in sql:
            db.execute(statement)
        self.assertEqual(('Keep label', 'Keep note', None, None), db.execute('SELECT user_name,note,wifi_role,last_loc_source FROM entities').fetchone())
        self.assertEqual(('ble:keep', None, None), db.execute('SELECT entity_id,loc_source,origin FROM encounters').fetchone())
        _assert_matches(self, db, 8)
        db.close()


class MigrationNineTest(unittest.TestCase):
    """v9 (delete after sync): raw copies of Edge records nothing else reads yet."""

    def test_upgrade_keeps_edge_data_and_matches_room_schema(self):
        db, before = _create(8)
        _insert_minimal(db, before, 'edge_devices', device_id='9f3c00aa11bb22cc', name='Sotreus Edge', suffix='96FF', sync_cursor=4_813,
                        data_link='BLUETOOTH', deployment='TRAVEL', phone_tagging=1)
        _insert_minimal(db, before, 'edge_sightings', device_id='9f3c00aa11bb22cc', seq=7, entity_id='ble:keep', loc_kind='NOT_KNOWN')
        sql = _migration_sql('MIGRATION_8_9', 'val ALL')
        self.assertEqual(2, len(sql))
        for statement in sql:
            db.execute(statement)
        self.assertEqual(4_813, db.execute('SELECT sync_cursor FROM edge_devices').fetchone()[0])
        self.assertEqual(1, db.execute('SELECT COUNT(*) FROM edge_sightings').fetchone()[0])
        db.execute("INSERT INTO edge_kept_records VALUES ('9f3c00aa11bb22cc',8,5,1,1,10,20,'00',30)")
        # (device_id, seq) is unique: a record handed off twice is kept once.
        with self.assertRaises(sqlite3.IntegrityError):
            db.execute("INSERT INTO edge_kept_records VALUES ('9f3c00aa11bb22cc',8,5,1,1,10,20,'00',31)")
        _assert_matches(self, db, 9)
        db.close()


if __name__ == '__main__':
    unittest.main()
