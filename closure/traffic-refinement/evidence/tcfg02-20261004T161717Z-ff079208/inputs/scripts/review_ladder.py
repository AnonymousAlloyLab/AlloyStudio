"""Current advisory review ladder; historical frozen blocks retain their policy."""
import json

from verify_lean import Rejected, digest, inside

TIERS = (('luna', 'gpt-6-luna'), ('sol', 'gpt-6.1-sol'), ('astra', 'gpt-6-astra'))


def check_reviews(root, block_path):
    block_file = inside(root, block_path)
    block = json.loads(block_file.read_text())
    block_hash = digest(block_file)
    prior, records = {}, {}
    for tier, (prefix, model) in enumerate(TIERS, 1):
        current = {}
        for suffix in ('a', 'b'):
            relative = f'formal/reviews/{block["id"]}/{prefix}-{suffix}.json'
            report = json.loads(inside(root, relative).read_text())
            if (not isinstance(report, dict) or report.get('reviewerModel') != model
                    or report.get('tier') != tier
                    or report.get('blockManifestSha256') != block_hash
                    or report.get('priorReviews') != prior):
                raise Rejected('Review model, source or tier binding mismatch: ' + relative)
            if report.get('verdict') != 'no_constructed_breach' or report.get('findings') != []:
                raise Rejected('Unresolved constructed review finding: ' + relative)
            notes = relative.removesuffix('.json') + '.md'
            notes_hash = digest(inside(root, notes))
            if report.get('notesSha256') != notes_hash:
                raise Rejected('Review notes binding mismatch: ' + notes)
            current[relative] = digest(root / relative)
            records[notes] = notes_hash
        records.update(current)
        prior.update(current)
    return records
