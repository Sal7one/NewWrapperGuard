#!/usr/bin/env python3
"""Validate documented integration profiles against the shipped JSON schemas."""
import json
from pathlib import Path
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]


def main():
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
    print(f"Integration schemas: {len(profiles)} profiles, all six formats, and disabled host registrations passed.")


if __name__ == "__main__":
    main()
