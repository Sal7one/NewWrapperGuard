import { Finding, ScanSettings } from '../models';
import { findingFingerprint } from './fingerprints';
import { maskSecretValues } from './redaction';
import { isHostAllowed } from './urlAllowlist';
const sax = require('sax') as {parser(strict:boolean,options:unknown):{onopentag?:(node:{local:string})=>void;write(value:string):{close():void}}};
type TextSource={relativePath:string;content:string};
export function supportedMavenPom(source: TextSource): boolean {
  if (/<!DOCTYPE|<!ENTITY/i.test(source.content)) return false;
  try {
    const parser=sax.parser(true,{xmlns:true}); let first:string|undefined;
    parser.onopentag=node=>{ first ??= node.local; };
    parser.write(source.content).close();return first === 'project';
  } catch {return false;}
}
function decodeXml(value:string):string {
  return value.replace(/&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);/g,(raw,entity:string)=>{
    const named:Record<string,string>={amp:'&',lt:'<',gt:'>',quot:'"',apos:"'"};
    if (named[entity]) return named[entity];
    try {return String.fromCodePoint(Number.parseInt(entity.slice(entity.startsWith('#x') ? 2 : 1),entity.startsWith('#x') ? 16 : 10));} catch {return raw;}
  });
}
export function analyzeMavenPom(source:TextSource,settings:ScanSettings):Finding[] {
  if (!supportedMavenPom(source)) return [];
  const text=source.content.replace(/<!--[\s\S]*?-->|<!\[CDATA\[[\s\S]*?]]>/g,value=>' '.repeat(value.length));
  const findings:Finding[]=[];
  const add=(ruleId:string,title:string,remediation:string,category:string,offset:number,raw:string):void=>{
    const masked=maskSecretValues(raw.slice(0,160));
    const evidence={text:masked.masked,redacted:masked.changed};
    findings.push({fingerprint:findingFingerprint(ruleId,source.relativePath,evidence.text),ruleId,title,message:title,severity:'WARN',confidence:'MEDIUM',category,kind:'ACTIONABLE',location:{path:source.relativePath,line:(text.slice(0,offset).match(/\n/g)||[]).length+1},evidence,remediation,reachable:true,callChain:[],policyAction:'REVIEW',status:'ACTIVE'});
  };
  for (const repository of text.matchAll(/<((?:[A-Za-z_][\w.-]*:)?(?:repository|pluginRepository))\b[^>]*>([\s\S]*?)<\/\1\s*>/g)) {
    for (const match of repository[2].matchAll(/<(?:[A-Za-z_][\w.-]*:)?url\b[^>]*>([^<]*)<\/(?:[A-Za-z_][\w.-]*:)?url\s*>/g)) {
      const url=decodeXml(match[1]).trim(); if (!url || url.includes('${')) continue;
      const offset=repository.index!+repository[0].indexOf(repository[2])+match.index!;
      if (!/^https:\/\//i.test(url)) add('WG-MAVEN-001','Maven repository does not use HTTPS','Use an approved HTTPS repository URL.','REPOSITORY',offset,url);
      else if (!isHostAllowed(url,settings.knownSafeRepositoryHosts)) add('WG-MAVEN-002','Maven repository host is not allowlisted','Review the repository owner and configure an approved repository host.','REPOSITORY',offset,url);
    }
  }
  for (const match of text.matchAll(/<(?:[A-Za-z_][\w.-]*:)?version\b[^>]*>([^<]*)<\/(?:[A-Za-z_][\w.-]*:)?version\s*>/g)) {
    const version=decodeXml(match[1]).trim();
    if (version.includes('+') || /SNAPSHOT/i.test(version) || ['LATEST','RELEASE'].includes(version.toUpperCase()) || /^[[(]/.test(version)) add('WG-MAVEN-003','Maven version is not pinned','Pin a reviewed exact version for reproducible dependency resolution.','SUPPLY_CHAIN',match.index!,version);
  }
  return findings;
}
