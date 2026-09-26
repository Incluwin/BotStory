"""Extract successful PLAYER learning only; never turn model guesses into ground truth."""
import argparse
import gzip
import hashlib
import json
import re
from pathlib import Path

CONFIRMED = re.compile(r'\[phrase\] learned "(.*)" -> (.*?) \(PLAYER [\d.]+\)')


def collect(log_root):
    labels = {}
    records = 0
    for path in sorted(log_root.rglob('*')):
        if not path.is_file() or not (path.name.endswith('.log') or path.name.endswith('.log.gz')):
            continue
        opener = gzip.open if path.name.endswith('.gz') else open
        with opener(path, 'rt', encoding='utf-8', errors='replace') as stream:
            for line in stream:
                match = CONFIRMED.search(line)
                if not match:
                    continue
                records += 1
                message, command = match.groups()
                labels.setdefault(message, set()).add(command)
    rows = []
    conflicts = []
    for message, commands in sorted(labels.items()):
        if len(commands) != 1:
            conflicts.append({'message': message, 'commands': sorted(commands)})
            continue
        rows.append({'id': 'player-' + hashlib.sha256(message.encode()).hexdigest()[:16],
                     'provenance': 'player_confirmed_successful_dispatch', 'split': 'confirmation',
                     'context_source': 'not_recorded_in_legacy_log',
                     'state': {'message': message}, 'acceptable_commands': sorted(commands)})
    return rows, {'matching_log_lines': records, 'unique_labels': len(rows), 'conflicts': conflicts}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--logs', type=Path, default=Path('logs'))
    parser.add_argument('--output', type=Path, default=Path('tools/jev-eval/confirmations.jsonl'))
    args = parser.parse_args()
    rows, audit = collect(args.logs)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(''.join(json.dumps(row) + '\n' for row in rows), encoding='utf-8')
    args.output.with_suffix('.audit.json').write_text(json.dumps(audit, indent=2), encoding='utf-8')
    print(f"Extracted {len(rows)} labels; excluded {len(audit['conflicts'])} conflicting phrases. Legacy context is unavailable.")
