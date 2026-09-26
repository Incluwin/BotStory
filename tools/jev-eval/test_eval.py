import gzip
import tempfile
import unittest
from pathlib import Path
from collect_confirmations import collect
from summarize import summarize


class EvaluationTests(unittest.TestCase):
    def test_only_successful_player_labels_are_used_and_duplicates_conflicts_handled(self):
        lines = '\n'.join([
            '[typesafe-chat] confirmed "wait please" -> stop',  # Not yet successfully dispatched.
            '[phrase] learned "wait please" -> stop (JEV 1.00)',
            '[phrase] learned "farm here please" -> farm here (PLAYER 1.00)',
            '[phrase] learned "come" -> follow me (PLAYER 1.00)',
            '[phrase] learned "come" -> move here (PLAYER 1.00)',
        ])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'current.log').write_text(lines)
            with gzip.open(root / 'archive.log.gz', 'wt') as stream:
                stream.write(lines)
            rows, audit = collect(root)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]['acceptable_commands'], ['farm here'])
        self.assertEqual(rows[0]['state'], {'message': 'farm here please'})
        self.assertEqual(len(audit['conflicts']), 1)

    def test_lower_ask_rate_does_not_hide_incorrect_automatic_actions(self):
        base = dict(provenance='synthetic', split='holdout', strategy='test', input_tokens=10,
                    elapsed_ms=20, calls=[{}])
        result = summarize({'observations': [
            dict(base, decision={'outcome': 'ACT'}, correct_command=False, false_act=True),
            dict(base, decision={'outcome': 'ASK'}, correct_command=True, false_act=False),
        ]})[0]
        self.assertEqual(result['ask_rate'], .5)
        self.assertEqual(result['command_accuracy'], .5)
        self.assertEqual(result['act_precision'], 0)
        self.assertEqual(result['false_acts'], 1)


if __name__ == '__main__':
    unittest.main()
