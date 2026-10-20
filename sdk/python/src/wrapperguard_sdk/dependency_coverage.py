"""Bounded inventory accounting, separate from vulnerability findings and host policy.

Versions are producer-reported identifiers. Counts do not prove a full transitive
dependency graph, package installation or exploit reachability. No resolver executes.
"""
from __future__ import annotations

import re


def _versioned(value: object) -> bool:
    return (isinstance(value, str) and 0 < len(value) <= 512 and value.lower() not in
            {"unknown", "unspecified", "latest", "release", "none", "null"} and
            not re.search(r"[\s*<>=^~\[\](){},$]", value) and not value.endswith("+"))


def summarize(format_name: str, document: object, *, require_resolved: bool) -> tuple[list[dict], bool]:
    inspected = versioned = sources = 0; unknown = False; limited = False
    def package(value, version_key):
        nonlocal inspected, versioned, unknown, limited
        inspected += 1
        if inspected > 10000:
            limited = True; return False
        if not isinstance(value, dict): unknown = True
        elif _versioned(value.get(version_key)): versioned += 1
        return True
    if not isinstance(document, dict):
        unknown = True
    elif format_name in {"osv", "trivy"}:
        records = document.get("results" if format_name == "osv" else "Results")
        if not isinstance(records, list): unknown = True
        else:
            for record in records:
                sources += 1
                if sources > 5000: limited = True; break
                if not isinstance(record, dict): unknown = True; continue
                inventory = record.get("packages" if format_name == "osv" else "Packages")
                if not isinstance(inventory, list): unknown = True; continue
                for item in inventory:
                    value = item.get("package") if format_name == "osv" and isinstance(item, dict) else item
                    if not package(value, "version" if format_name == "osv" else "Version"): break
                if limited: break
    elif format_name == "cyclonedx":
        components = document.get("components")
        if not isinstance(components, list): unknown = True
        else:
            stack = [(iter(components), 0)]
            while stack:
                iterator, depth = stack[-1]
                try: component = next(iterator)
                except StopIteration: stack.pop(); continue
                if depth > 64: limited = True; break
                if not package(component, "version"): break
                if isinstance(component, dict):
                    children = component.get("components", [])
                    if not isinstance(children, list): unknown = True
                    elif children: stack.append((iter(children), depth + 1))
    else:
        unknown = True
    total = min(inspected, 10000); missing = total - versioned
    incomplete = limited or (require_resolved and (unknown or total == 0 or missing > 0))
    message = (f"Reported inventory: {total} records, {versioned} with fixed version identifiers, {missing} unresolved or unversioned. "
               + ("Inventory completeness is unknown. " if unknown else "")
               + "These producer-reported counts do not prove full transitive resolution, reachability or whole-project coverage; no package manager was executed.")
    notices = [{"code": "WG-SDK-DEPENDENCY-COVERAGE", "message": message}]
    if incomplete:
        notices.append({"code": "WG-SDK-DEPENDENCY-COVERAGE-INCOMPLETE", "message":
            "The required versioned dependency inventory is unavailable, unresolved or exceeds coverage limits."})
    return notices, incomplete
