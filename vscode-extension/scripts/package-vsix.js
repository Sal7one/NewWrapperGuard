#!/usr/bin/env node
'use strict';

const {execFileSync,spawnSync}=require('node:child_process');
const path=require('node:path');

function packageArguments(commit,version){
  if(!/^[a-f0-9]{40,64}$/.test(commit))throw new Error('Packaging requires the exact Git source commit.');
  if(!/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(version))throw new Error('Invalid extension package version.');
  return ['package','--out','wrapperguard-'+version+'.vsix',
    '--baseContentUrl','https://github.com/Sal7one/NewWrapperGaurd/blob/'+commit+'/vscode-extension',
    '--baseImagesUrl','https://raw.githubusercontent.com/Sal7one/NewWrapperGaurd/'+commit+'/vscode-extension'];
}

function main(){
  const extension=path.resolve(__dirname,'..');
  const commit=execFileSync('git',['rev-parse','HEAD'],{cwd:path.dirname(extension),encoding:'utf8'}).trim();
  const version=require('../package.json').version;
  const result=spawnSync(process.execPath,[require.resolve('@vscode/vsce/vsce'),...packageArguments(commit,version)],
    {cwd:extension,stdio:'inherit',shell:false});
  if(result.error)throw result.error;
  if(result.signal)throw new Error('VSIX packaging stopped: '+result.signal);
  process.exitCode=result.status??1;
}

module.exports={packageArguments};
if(require.main===module)main();
