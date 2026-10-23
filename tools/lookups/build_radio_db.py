#!/usr/bin/env python3
"""Build RadioDb v1 from IEEE registries and Nordic's Bluetooth assigned-number JSON.

No third-party Python dependencies. Network access occurs only with --download. Keep the
source directory to reproduce a build; output records every source URL and SHA-256.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import csv
import hashlib
import io
import json
from pathlib import Path
import struct
import tempfile
import urllib.request
from datetime import date

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'sensing/android/src/main/assets/lookups'
NORDIC = 'https://raw.githubusercontent.com/nordicsemi/bluetooth-numbers-database/master/v1/'
APPEARANCE_BASELINE = Path(__file__).with_name('bluetooth_appearance_baseline.json')
SOURCES = {
    'mal.csv': 'https://standards-oui.ieee.org/oui/oui.csv',
    'mam.csv': 'https://standards-oui.ieee.org/oui28/mam.csv',
    'mas.csv': 'https://standards-oui.ieee.org/oui36/oui36.csv',
    'iab.csv': 'https://standards-oui.ieee.org/iab/iab.csv',
    'cid.csv': 'https://standards-oui.ieee.org/cid/cid.csv',
    'company_ids.json': NORDIC + 'company_ids.json',
    'gap_appearance.json': NORDIC + 'gap_appearance.json',
    'service_uuids.json': NORDIC + 'service_uuids.json',
}


def read_ieee(data: bytes, digits: int) -> dict[int, str]:
    entries = {}
    for row in csv.DictReader(io.StringIO(data.decode('utf-8-sig'))):
        assignment = row['Assignment'].strip()
        name = row['Organization Name'].strip()
        if len(assignment) != digits or not name:
            raise ValueError('Invalid IEEE assignment')
        key = int(assignment, 16)
        # IEEE publishes a few historical shared prefixes. Preserve all listed registrants.
        entries.setdefault(key, set()).add(name)
    return {key: ' / '.join(sorted(names)) for key, names in entries.items()}


def tables_from_sources(raw: dict[str, bytes]) -> dict[str, dict[int, str]]:
    long = {}
    for file, bits in [('mam.csv', 28), ('mas.csv', 36), ('iab.csv', 36)]:
        for prefix, name in read_ieee(raw[file], bits // 4).items():
            key = (bits << 56) | prefix
            if key in long and long[key] != name:
                raise ValueError('Conflicting long IEEE assignment')
            long[key] = name
    companies = {int(r['code']): r['name'] for r in json.loads(raw['company_ids.json'])}
    # Nordic intentionally covers a subset; retain official definitions outside that subset.
    appearances = {r['value']: r['name'] for r in json.loads(APPEARANCE_BASELINE.read_bytes())['entries']}
    for row in json.loads(raw['gap_appearance.json']):
        category = int(row['category']) << 6
        appearances[category] = row['name']
        for sub in row.get('subcategory', []):
            appearances[category | int(sub['value'])] = row['name'] + ': ' + sub['name']
    services = {}
    for row in json.loads(raw['service_uuids.json']):
        value = row['uuid'].replace('-', '').upper()
        if len(value) == 4:
            services[int(value, 16)] = row['name']
    return {'mal': read_ieee(raw['mal.csv'], 6), 'cid': read_ieee(raw['cid.csv'], 6),
            'long': long, 'btc': companies, 'app': appearances, 'uuid': services}


def pack(tables: dict[str, dict[int, str]], built: date) -> bytes:
    names = sorted({name for table in tables.values() for name in table.values()})
    if len(names) > 65_535:
        raise ValueError('RadioDb v1 name-index capacity exceeded')
    index = {name: i for i, name in enumerate(names)}
    sections = {}
    for tag, key_format in [('mal', 'I'), ('cid', 'I'), ('long', 'Q'), ('btc', 'H'), ('app', 'H'), ('uuid', 'H')]:
        rows = sorted(tables[tag].items())
        keys = b''.join(struct.pack('<' + key_format, key) for key, _ in rows)
        refs = b''.join(struct.pack('<H', index[name]) for _, name in rows)
        sections[tag] = struct.pack('<I', len(rows)) + keys + refs
    blob = bytearray()
    offsets = []
    for name in names:
        offsets.append(len(blob))
        blob.extend(name.encode('utf-8'))
    offsets.append(len(blob))
    sections['noff'] = struct.pack('<I', len(names)) + struct.pack('<' + 'I' * len(offsets), *offsets)
    sections['nstr'] = bytes(blob)
    header = struct.pack('<4sHHII', b'SPLK', 1, 0, int(built.strftime('%Y%m%d')), len(sections))
    offset = len(header) + 8 * len(sections)
    directory = bytearray()
    for tag, data in sections.items():
        directory.extend(struct.pack('<4sI', tag.encode(), offset))
        offset += len(data)
    return header + directory + b''.join(sections.values())


def counts(binary: bytes) -> dict[str, int]:
    magic, version, _, _, size = struct.unpack_from('<4sHHII', binary)
    if magic != b'SPLK' or version != 1:
        raise ValueError('Invalid RadioDb')
    result = {}
    for i in range(size):
        tag, offset = struct.unpack_from('<4sI', binary, 16 + 8 * i)
        tag = tag.rstrip(b'\0').decode()
        if tag in ('mal', 'cid', 'long', 'btc', 'app', 'uuid'):
            result[tag] = struct.unpack_from('<I', binary, offset)[0]
    return result


def atomic_write(path: Path, data: bytes):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as stream:
        stream.write(data)
        tmp = Path(stream.name)
    tmp.replace(path)


def download(directory: Path):
    def fetch(item):
        name, url = item
        request = urllib.request.Request(url, headers={'User-Agent': 'Sotreus-dataset-builder/1'})
        with urllib.request.urlopen(request, timeout=30) as response:
            data = response.read(20_000_001)
        if len(data) > 20_000_000:
            raise ValueError('Oversized source: ' + name)
        return name, data
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        fetched = dict(pool.map(fetch, SOURCES.items()))
    # Download the complete snapshot before modifying any cached source.
    tables_from_sources(fetched)
    for name, data in fetched.items():
        atomic_write(directory / name, data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-dir', type=Path, required=True)
    parser.add_argument('--date', type=date.fromisoformat, required=True, help='Snapshot date, YYYY-MM-DD')
    parser.add_argument('--output-dir', type=Path, default=ASSETS)
    parser.add_argument('--download', action='store_true')
    parser.add_argument('--allow-shrink', action='store_true', help='Explicitly accept a reduction of more than 5% in a table')
    args = parser.parse_args()
    if args.download:
        download(args.source_dir)
    raw = {name: (args.source_dir / name).read_bytes() for name in SOURCES}
    tables = tables_from_sources(raw)
    data = pack(tables, args.date)
    new_counts = counts(data)
    baseline = args.output_dir / 'radiodb.bin'
    if baseline.exists() and not args.allow_shrink:
        for tag, old in counts(baseline.read_bytes()).items():
            if new_counts[tag] < old * 0.95:
                raise ValueError(f'{tag} coverage decreased: {old} -> {new_counts[tag]}; inspect sources before accepting')
    if new_counts['mal'] < 30_000 or new_counts['btc'] < 3_000:
        raise ValueError('Incomplete source snapshot')
    metadata = {'built': args.date.isoformat(), 'format': 1, 'counts': new_counts,
                'binary_sha256': hashlib.sha256(data).hexdigest(),
                'sources': {name: {'url': url, 'sha256': hashlib.sha256(raw[name]).hexdigest()} for name, url in SOURCES.items()}}
    metadata['sources']['bluetooth_appearance_baseline.json'] = {
        'url': 'https://www.bluetooth.com/specifications/assigned-numbers/', 'snapshot': '2026-08-24',
        'sha256': hashlib.sha256(APPEARANCE_BASELINE.read_bytes()).hexdigest()}
    manifest = f'built {args.date.isoformat()}\nbytes {len(data)}\n' + ''.join(f'{tag} {value}\n' for tag, value in new_counts.items())
    manifest += 'sources radiodb.sources.json (URLs and SHA-256; Bluetooth definitions via Nordic Semiconductor)\n'
    atomic_write(args.output_dir / 'radiodb.sources.json', (json.dumps(metadata, indent=2) + '\n').encode())
    atomic_write(args.output_dir / 'radiodb.txt', manifest.encode())
    atomic_write(baseline, data)
    print(manifest, end='')


if __name__ == '__main__':
    main()
