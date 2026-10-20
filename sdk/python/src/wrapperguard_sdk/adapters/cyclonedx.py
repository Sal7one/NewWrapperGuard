"""CycloneDX 1.4–1.7 JSON vulnerability/VEX data; inventory alone is not a scan.

VEX statements are attributed context. They never become trusted host exceptions.
No external references are fetched and no package manager is executed.
"""
from . import required_object, required_list, plain_html, text

_SEVERITIES = {'critical': 'ERROR', 'high': 'ERROR', 'medium': 'WARN', 'low': 'WARN',
               'info': 'INFO', 'none': 'INFO', 'unknown': 'WARN'}
_STATES = {'resolved', 'resolved_with_pedigree', 'exploitable', 'in_triage', 'false_positive', 'not_affected'}


def parse(document, ctx, scope=None):
    report = required_object(document, 'CycloneDX')
    if report.get('bomFormat') != 'CycloneDX' or report.get('specVersion') not in {'1.4', '1.5', '1.6', '1.7'}:
        raise ValueError('Unsupported CycloneDX envelope')
    components = {}
    stack = [(iter(required_list(report.get('components', []), 'components')), 0)]
    metadata = report.get('metadata', {})
    if not isinstance(metadata, dict):
        ctx.problem(); metadata = {}
    if 'component' in metadata:
        stack.append((iter((metadata['component'],)), 0))
    inspected = 0
    while stack:
        iterator, depth = stack[-1]
        try:
            component = next(iterator)
        except StopIteration:
            stack.pop(); continue
        inspected += 1
        if inspected > 10000 or depth > 64:
            ctx.problem('WG-REPORT-LIMIT', 'Component inventory exceeds the bounded import scope.'); break
        if not isinstance(component, dict):
            ctx.problem(); continue
        reference = component.get('bom-ref')
        if reference is not None:
            if not isinstance(reference, str) or not reference or len(reference) > 4096 or reference in components:
                ctx.problem()
            else:
                components[reference] = component
        nested = component.get('components', [])
        if not isinstance(nested, list):
            ctx.problem(); continue
        if nested:
            stack.append((iter(nested), depth + 1))
    if 'vulnerabilities' not in report:
        ctx.problem('WG-SBOM-INVENTORY', 'This SBOM contains inventory without a vulnerability assessment; inventory alone cannot establish clean security coverage.')
        return
    for vulnerability in required_list(report['vulnerabilities'], 'vulnerabilities'):
        if not isinstance(vulnerability, dict) or not isinstance(vulnerability.get('id'), str) or not vulnerability['id']:
            ctx.problem(); continue
        ratings = vulnerability.get('ratings', [])
        if not isinstance(ratings, list):
            ctx.problem(); ratings = []
        severities = []
        for rating in ratings:
            if not isinstance(rating, dict):
                ctx.problem(); continue
            if 'severity' in rating:
                severities.append(ctx.severity(rating['severity'], _SEVERITIES))
            elif 'score' in rating:
                score = rating['score']
                if isinstance(score, (int, float)) and not isinstance(score, bool) and 0 <= score <= 10:
                    severities.append('ERROR' if score >= 7 else 'WARN' if score > 0 else 'INFO')
                else:
                    ctx.problem()
        # A known vulnerability without a supplied rating remains a review warning.
        severity = max(severities or ['WARN'], key=lambda item: {'INFO': 0, 'WARN': 1, 'ERROR': 2}[item])
        analysis = vulnerability.get('analysis', {})
        if not isinstance(analysis, dict):
            ctx.problem(); analysis = {}
        state = analysis.get('state')
        if state is not None and (not isinstance(state, str) or state not in _STATES):
            ctx.problem(); state = None
        affects = vulnerability.get('affects')
        if not isinstance(affects, list) or not affects:
            ctx.problem('WG-SBOM-AFFECTS', 'A vulnerability has no resolvable affected component.'); continue
        for affected in affects:
            if not isinstance(affected, dict) or not isinstance(affected.get('ref'), str) or affected['ref'] not in components:
                ctx.problem('WG-SBOM-AFFECTS', 'An affected component reference could not be resolved.'); continue
            component = components[affected['ref']]
            name = text(component.get('name'), 'dependency'); version = text(component.get('version'), 'unspecified')
            identifier = text(vulnerability['id'])
            summary = plain_html(vulnerability.get('description')) or 'Known dependency vulnerability'
            message = f'Dependency {name}@{version}: {identifier}. {summary}'
            if state:
                message += f' Producer VEX state: {state}; this statement is not a host-approved exception.'
            ctx.add(rule_id=identifier, title=f'{identifier} in {name}', message=message, severity=severity,
                    path='.', category='SUPPLY_CHAIN', remediation=plain_html(vulnerability.get('recommendation')) or
                    'Review the advisory and producer VEX evidence, then update the dependency or explicitly review the finding.')
