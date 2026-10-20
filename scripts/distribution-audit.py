#!/usr/bin/env python3
"""Inventory actual packaged runtimes; optional explicit live OSV metadata queries."""
from __future__ import annotations
import argparse
from email.parser import BytesParser
import hashlib
import io
import json
from pathlib import Path
import re
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
JARS = {
    'annotations': ('org.jetbrains', '13.0'),
    'kotlin-stdlib': ('org.jetbrains.kotlin', '2.2.20'),
    'kotlinx-serialization-core-jvm': ('org.jetbrains.kotlinx', '1.8.0'),
    'kotlinx-serialization-json-jvm': ('org.jetbrains.kotlinx', '1.8.0'),
}
# Reviewed bytes from the pinned sax 1.6.1 npm package, independent of a local install.
SAX_JS_SHA256 = '1cdc1270d987cb0f6ee66dd8efb01c50511aa14d17b6163bbbffb21df8cf1c06'
SAX_LICENSE_SHA256 = '8a1af140fdfbf5afd3df27f7e662f989c5b963a300020dfafce42033cae9e004'


def digest(data): return hashlib.sha256(data).hexdigest()


def archive_names(package):
    names = package.namelist()
    if len(names) != len(set(names)) or any(name.startswith('/') or '\\' in name or '..' in name.split('/') for name in names):
        raise ValueError('Duplicate or unsafe packaged archive entries')
    return {name for name in names if not name.endswith('/')}


def audit_wheel(wheel_bytes, sdk, root):
    prefix = f'wrapperguard_sdk-{sdk}.dist-info/'
    with zipfile.ZipFile(io.BytesIO(wheel_bytes)) as package:
        names = archive_names(package)
        if any(not name.startswith(('wrapperguard_sdk/', prefix)) for name in names):
            raise ValueError('Unreviewed SDK package or runtime dependency')
        metadata = BytesParser().parsebytes(package.read(prefix+'METADATA'))
        if (metadata.get('Name') != 'wrapperguard-sdk' or metadata.get('Version') != sdk
                or metadata.get('Requires-Python') != '>=3.10' or metadata.get('License-Expression') != 'Apache-2.0'):
            raise ValueError('SDK identity, version, Python or license metadata changed')
        if metadata.get_all('Requires-Dist'):
            raise ValueError('SDK runtime dependency closure requires a new review')
        if package.read(prefix+'licenses/LICENSE') != (root/'LICENSE').read_bytes():
            raise ValueError('SDK Apache license is missing or differs')
        source = root/'sdk/python/src/wrapperguard_sdk'
        expected = {path.relative_to(source).as_posix(): path.read_bytes() for path in source.rglob('*')
                    if path.is_file() and '__pycache__' not in path.parts}
        actual = {name.removeprefix('wrapperguard_sdk/'): package.read(name)
                  for name in names if name.startswith('wrapperguard_sdk/')}
        if not expected or actual != expected:
            raise ValueError('SDK runtime files differ from the current reviewed source')


def inventory(root=None):
    root = Path(root) if root is not None else ROOT
    version = re.search(r'version = "([^"]+)"', (root/'build.gradle.kts').read_text()).group(1)
    sdk = re.search(r'^version = "([^"]+)"', (root/'sdk/python/pyproject.toml').read_text(), re.M).group(1)
    components = {}
    cli = root/'wrapperguard-cli/build/install/wrapperguard-cli'
    cli_jars = {}
    for path in sorted((cli/'lib').glob('*.jar')):
        key = next((name for name, (_, v) in JARS.items() if path.name == f'{name}-{v}.jar'), None)
        if key:
            group, v = JARS[key]
        elif path.name in {f'wrapperguard-{name}-{version}.jar' for name in ('core', 'rules', 'cli')}:
            key = path.name.removesuffix(f'-{version}.jar');group = 'com.sal7one.wrapperguard';v = version
        else:
            raise ValueError('Unreviewed packaged runtime JAR: '+path.name)
        purl = f'pkg:maven/{group}/{key}@{v}'
        cli_jars[path.name] = path.read_bytes()
        components[purl] = {'type':'library','name':key,'group':group,'version':v,'purl':purl,'bom-ref':purl,
            'licenses':[{'license':{'id':'Apache-2.0'}}], 'hashes':[{'alg':'SHA-256','content':digest(path.read_bytes())}]}
    if len(components) != 7:
        raise ValueError('Build the current CLI before inventorying all seven runtime JARs')
    for name in ('LICENSE', 'THIRD_PARTY_NOTICES.md'):
        if (cli/name).read_bytes() != (root/name).read_bytes():
            raise ValueError('CLI license/notice is missing or differs')
    cli_zip = root/f'wrapperguard-cli/build/distributions/wrapperguard-cli-{version}.zip'
    with zipfile.ZipFile(cli_zip) as package:
        names = archive_names(package)
        prefix = f'wrapperguard-cli-{version}/'
        expected = {prefix+path.relative_to(cli).as_posix(): path.read_bytes()
                    for path in cli.rglob('*') if path.is_file()}
        if {name: package.read(name) for name in names} != expected:
            raise ValueError('Packaged CLI files differ from the reviewed installed distribution')
    wheel = root/f'build/sdk/wrapperguard_sdk-{sdk}-py3-none-any.whl'
    wheel_bytes = wheel.read_bytes()
    audit_wheel(wheel_bytes, sdk, root)
    native = root/f'wrapperguard-intellij/build/distributions/wrapperguard-intellij-{version}.zip'
    vsix = root/f'vscode-extension/wrapperguard-{version}.vsix'
    with zipfile.ZipFile(native) as package:
        names = archive_names(package)
        required = {f'{key}-{v}.jar' for key, (_,v) in JARS.items()} | {
            f'wrapperguard-{part}-{version}.jar' for part in ('core','rules','intellij')}
        optional = {f'wrapperguard-intellij-{version}-searchableOptions.jar'}
        runtime_jars = {name for name in names if name.endswith('.jar')}
        leaves = {Path(name).name for name in runtime_jars}
        if not required <= leaves or leaves - required - optional or any(name != 'wrapperguard-intellij/lib/'+Path(name).name for name in runtime_jars):
            raise ValueError('Native runtime dependency closure is missing, duplicated or unreviewed')
        for name in runtime_jars:
            leaf = Path(name).name
            if leaf in cli_jars and package.read(name) != cli_jars[leaf]:
                raise ValueError('Native and CLI shared runtime JARs differ: '+leaf)
        main = 'wrapperguard-intellij/lib/'+f'wrapperguard-intellij-{version}.jar'
        with zipfile.ZipFile(io.BytesIO(package.read(main))) as jar:
            jar_names = archive_names(jar)
            if {name for name in jar_names if name.endswith('.whl')} != {f'integrations/{wheel.name}'}:
                raise ValueError('Native SDK wheel closure differs')
            if jar.read(f'integrations/{wheel.name}') != wheel_bytes: raise ValueError('Native SDK wheel differs')
            for name, source in [('META-INF/LICENSE','LICENSE'),('META-INF/THIRD_PARTY_NOTICES.md','THIRD_PARTY_NOTICES.md')]:
                if jar.read(name) != (root/source).read_bytes(): raise ValueError('Native license/notice is missing or differs')
    lock = json.loads((root/'vscode-extension/package-lock.json').read_text())
    runtime = {name:data for name,data in lock['packages'].items() if name and not data.get('dev')}
    if set(runtime) != {'node_modules/sax'}: raise ValueError('New Node runtime dependencies need review')
    sax = runtime['node_modules/sax']
    if sax['version'] != '1.6.1' or sax.get('license') != 'BlueOak-1.0.0': raise ValueError('SAX identity/license changed')
    with zipfile.ZipFile(vsix) as package:
        names = archive_names(package)
        wheels = [name for name in names if name.endswith('.whl')]
        if len(wheels)!=1 or package.read(wheels[0]) != wheel_bytes: raise ValueError('VS Code SDK wheel differs')
        extension = json.loads(package.read('extension/package.json'))
        if extension.get('version') != version or extension.get('license') != 'Apache-2.0' or extension.get('dependencies') != {'sax':'1.6.1'} or extension.get('optionalDependencies'):
            raise ValueError('VS Code package identity/license/runtime dependencies differ')
        node_files = {name for name in names if name.startswith('extension/node_modules/')}
        required = {'extension/node_modules/sax/package.json','extension/node_modules/sax/lib/sax.js'}
        licenses = {'extension/node_modules/sax/LICENSE.md','extension/node_modules/sax/LICENSE'}
        if not required <= node_files or len(node_files & licenses) != 1 or node_files - required - licenses:
            raise ValueError('Unreviewed or missing packaged Node runtime files')
        bundled_sax = json.loads(package.read('extension/node_modules/sax/package.json'))
        if (bundled_sax.get('name') != 'sax' or bundled_sax.get('version') != sax['version']
                or bundled_sax.get('license') != sax['license'] or bundled_sax.get('main') != 'lib/sax.js'
                or bundled_sax.get('dependencies') or bundled_sax.get('optionalDependencies') or bundled_sax.get('bundledDependencies')):
            raise ValueError('Bundled SAX identity or dependency closure changed')
        sax_bytes = package.read('extension/node_modules/sax/lib/sax.js')
        if digest(sax_bytes) != SAX_JS_SHA256 or digest(package.read(next(iter(node_files & licenses)))) != SAX_LICENSE_SHA256:
            raise ValueError('Bundled SAX runtime/license bytes differ from the reviewed npm package')
        if package.read('extension/LICENSE.txt') != (root/'LICENSE').read_bytes() or package.read('extension/resources/THIRD_PARTY_NOTICES.md') != (root/'THIRD_PARTY_NOTICES.md').read_bytes():
            raise ValueError('VS Code Apache license/notice is missing or differs')
    for name,v,purl,license_id,sha in [('wrapperguard-sdk',sdk,f'pkg:pypi/wrapperguard-sdk@{sdk}','Apache-2.0',digest(wheel_bytes)),
                                      ('sax',sax['version'],f'pkg:npm/sax@{sax["version"]}','BlueOak-1.0.0',digest(sax_bytes))]:
        component={'type':'library','name':name,'version':v,'purl':purl,'bom-ref':purl,'licenses':[{'license':{'id':license_id}}]}
        if sha: component['hashes']=[{'alg':'SHA-256','content':sha}]
        components[purl]=component
    return {'bomFormat':'CycloneDX','specVersion':'1.6','version':1,'components':list(components.values())}, {
        'schemaVersion':1,'appVersion':version,'sdkVersion':sdk,'bundledWheelsIdentical':True,
        'unknownRuntimeDependencies':[], 'artifactSha256':{path.name:digest(path.read_bytes()) for path in (native,vsix,wheel,cli_zip)},
        'scope':'Packaged runtime inventory only; optional scanners, platform/runtime software and build dependencies excluded'}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--query-osv',action='store_true',help='Send five dependency names/versions only to api.osv.dev')
    args=parser.parse_args()
    if not args.output.is_absolute() or args.output.exists():parser.error('Choose a new absolute output directory')
    bom, report=inventory()
    if args.query_osv:
        queries=[{'package':{'ecosystem':'Maven','name':group+':'+name},'version':version} for name,(group,version) in JARS.items()]
        queries.append({'package':{'ecosystem':'npm','name':'sax'},'version':'1.6.1'})
        body=json.dumps({'queries':queries}).encode()
        request=urllib.request.Request('https://api.osv.dev/v1/querybatch',data=body,headers={'Content-Type':'application/json'})
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(request,timeout=30) as response:
            if response.geturl() != request.full_url: raise ValueError('Unexpected advisory-service redirect')
            raw=response.read(1024*1024+1)
        if len(raw)>1024*1024:raise ValueError('Oversized advisory response')
        result=json.loads(raw)
        if len(result.get('results',[]))!=len(queries):raise ValueError('Partial advisory response')
        report['osvQueries']=[{**query,'result':row} for query,row in zip(queries,result['results'])]
        report['knownAdvisoryCount']=sum(len(row.get('vulns',[])) for row in result['results'])
        report['advisoryLimit']='Live package-database query; no findings is not proof of no vulnerabilities'
    args.output.mkdir(parents=True)
    (args.output/'runtime.cdx.json').write_text(json.dumps(bom,indent=2)+'\n')
    (args.output/'distribution-audit.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
    if report.get('knownAdvisoryCount',0):raise SystemExit(2)


if __name__=='__main__':main()
