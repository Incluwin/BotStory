"""Report accuracy AND automatic-action errors, with provenance/split kept separate."""
import argparse
import json
from collections import defaultdict
from pathlib import Path
from statistics import mean, median


def summarize(report):
    groups = defaultdict(list)
    for row in report['observations']:
        groups[(row['provenance'], row['split'], row['strategy'])].append(row)
    out = []
    for (provenance, split, strategy), rows in sorted(groups.items()):
        n = len(rows)
        acts = sum(r['decision']['outcome'] == 'ACT' for r in rows)
        false_acts = sum(r['false_act'] for r in rows)
        out.append(dict(provenance=provenance, split=split, strategy=strategy, n=n,
                        command_accuracy=sum(r['correct_command'] for r in rows) / n,
                        ask_rate=sum(r['decision']['outcome'] == 'ASK' for r in rows) / n,
                        act_rate=acts / n, false_acts=false_acts,
                        act_precision=(acts - false_acts) / acts if acts else None,
                        unavailable=sum(r['decision']['outcome'] == 'UNAVAILABLE' for r in rows),
                        mean_input_tokens=round(mean(r['input_tokens'] for r in rows)),
                        median_ms=round(median(r['elapsed_ms'] for r in rows)),
                        mean_requests=round(mean(len(r['calls']) for r in rows), 2)))
    return out


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=Path)
    args = parser.parse_args()
    summary = summarize(json.loads(args.report.read_text()))
    args.report.with_suffix('.summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    print(json.dumps(summary, indent=2))
