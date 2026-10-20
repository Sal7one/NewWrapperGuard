"""Sonar generic external issues (legacy issue fields and current rules/impacts).

This imports the open exchange format; it does not connect to a Sonar server.
"""
import hashlib
import json
import re

from . import required_object, required_list, text, plain_html

_SEVERITIES = {'BLOCKER': 'ERROR', 'CRITICAL': 'ERROR', 'HIGH': 'ERROR', 'MAJOR': 'WARN',
               'MEDIUM': 'WARN', 'MINOR': 'WARN', 'LOW': 'WARN', 'INFO': 'INFO'}


def _host_rule_id(engine: str, rule_id: str) -> str:
    original = 'SONAR/' + engine + '/' + rule_id
    if '/' not in engine and '/' not in rule_id and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.:/-]{0,119}', original):
        return original
    # JSON frames both producer fields: separators and normalized display slugs
    # cannot merge distinct external identities or reuse another rule's baseline.
    identity = json.dumps([engine, rule_id], ensure_ascii=True, separators=(',', ':')).encode('ascii')
    digest = hashlib.sha256(identity).hexdigest()
    def slug(value: str, fallback: str) -> str:
        return re.sub(r'[^A-Za-z0-9_.:-]+', '-', text(value)).strip('-')[:20] or fallback
    return 'SONAR/' + slug(engine, 'engine') + '/' + slug(rule_id, 'rule') + '/' + digest


def parse(document, ctx, scope=None):
    report = required_object(document, 'Sonar generic issue')
    rules = {}
    for rule in required_list(report.get('rules', []), 'Sonar rules'):
        if not isinstance(rule, dict) or not isinstance(rule.get('id'), str) or not rule['id'] or rule['id'] in rules:
            ctx.problem(); continue
        rules[rule['id']] = rule
    for issue in required_list(report.get('issues'), 'Sonar issues'):
        if not isinstance(issue, dict) or not isinstance(issue.get('ruleId'), str) or not issue['ruleId']:
            ctx.problem(); continue
        rule = rules.get(issue['ruleId'], {})
        engine = issue.get('engineId', rule.get('engineId'))
        location = issue.get('primaryLocation')
        if not isinstance(engine, str) or not engine or not isinstance(location, dict):
            ctx.problem(); continue
        path = ctx.location(location.get('filePath'))
        if path is None:
            continue
        values = []
        impacts = rule.get('impacts', [])
        if not isinstance(impacts, list):
            ctx.problem(); impacts = []
        for impact in impacts:
            if not isinstance(impact, dict):
                ctx.problem(); continue
            values.append(ctx.severity(impact.get('severity'), _SEVERITIES))
        raw = issue.get('severity', rule.get('severity'))
        if raw is not None:
            values.append(ctx.severity(raw, _SEVERITIES))
        if not values:
            ctx.problem('WG-REPORT-SEVERITY', 'A generic external issue has no recognized severity.')
        severity = max(values or ['INFO'], key=lambda item: {'INFO': 0, 'WARN': 1, 'ERROR': 2}[item])
        region = location.get('textRange', {})
        if not isinstance(region, dict):
            ctx.problem(); region = {}
        column = region.get('startColumn')
        # Sonar columns are zero based; the host uses one based source positions.
        if column is not None:
            if type(column) is not int or column < 0 or column >= 2147483647:
                ctx.problem(); column = None
            else:
                column += 1
        message = plain_html(location.get('message'))
        if not message:
            ctx.problem(); continue
        attribution = ' Producer: ' + text(engine)[:160] + '; rule: ' + text(issue['ruleId'])[:160] + '.'
        message = message[:max(0, 4096 - len(attribution))] + attribution
        ctx.add(rule_id=_host_rule_id(engine, issue['ruleId']),
                title=text(rule.get('name'), message[:160] or issue['ruleId'])[:512], message=message,
                severity=severity, path=path, line=region.get('startLine'), column=column,
                remediation=plain_html(rule.get('description')) or None)
