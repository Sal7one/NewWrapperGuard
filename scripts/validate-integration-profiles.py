#!/usr/bin/env python3
"""Validate documented integration profiles against the shipped JSON schemas."""
import json
from pathlib import Path
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]


def main():
    catalog = json.loads((ROOT / "catalog/security-methods.json").read_text(encoding="utf-8"))
    assert catalog["schemaVersion"] == 1
    methods = catalog["methods"]
    assert len({m["id"] for m in methods}) == len(methods), "Duplicate method identity."
    assert all(m["category"] in {"BUILT_IN", "SAST", "SUPPLY_CHAIN", "DAST", "RESEARCH", "AI"} for m in methods)
    assert all(type(m["defaultEnabled"]) is bool and m["defaultEnabled"] == (m["category"] == "BUILT_IN") for m in methods)
    assert all(m["providerId"] == "wrapperguard" for m in methods if m["category"] == "BUILT_IN")
    external = [m["providerId"] for m in methods if m["category"] != "BUILT_IN"]
    assert len(external) == len(set(external)), "Optional method providers must be independently addressable."
    schema = json.loads((ROOT / "schema/wrapperguard-integration-profile.schema.json").read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema)
    profiles = list((ROOT / "examples/integrations").glob("*.profile.json"))
    formats = set()
    for path in profiles:
        profile = json.loads(path.read_text(encoding="utf-8"))
        validator.validate(profile)
        formats.add(profile["format"])
    assert formats == set(schema["properties"]["format"]["enum"]), "Examples must cover every shipped report format."
    host_schema = json.loads((ROOT / "schema/wrapperguard-provider-config.schema.json").read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(host_schema)
    host = json.loads((ROOT / "examples/integrations/host-providers.example.json").read_text(encoding="utf-8"))
    Draft202012Validator(host_schema).validate(host)
    assert all(not p["permissions"]["enabled"] for p in host["providers"]), "Examples must require explicit provider enablement."
    print(f"Integration schemas: {len(methods)} methods, {len(profiles)} profiles, all eight formats, and disabled host registrations passed.")


if __name__ == "__main__":
    main()
