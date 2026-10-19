#!/usr/bin/env node
'use strict';
const http=require('node:http');
const fs=require('node:fs');
const path=require('node:path');
function servePreview(port=4173,demoRoot=path.resolve(__dirname,'..'),host='127.0.0.1') {
  const headers={'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'};
  const root=fs.realpathSync(demoRoot);
  const aliases={'/':'preview/vscode.html','/vscode.html':'preview/vscode.html','/preview/vscode.html':'preview/vscode.html','/index.html':'index.html','/README.md':'README.md','/preview/README.md':'preview/README.md','/screenshots/manifest.json':'screenshots/manifest.json'};
  const server=http.createServer((request,response)=>{
    const reject=(status,message,extra={})=>{response.writeHead(status,{...headers,'Content-Type':'text/plain; charset=utf-8',...extra});response.end(request.method==='HEAD'?undefined:message);};
    async function handle(){
      if(request.method!=='GET'&&request.method!=='HEAD'){reject(405,'Method not allowed',{'Allow':'GET, HEAD'});return;}
      let pathname;
      try{pathname=decodeURIComponent(new URL(request.url,'http://127.0.0.1').pathname);}catch{reject(400,'Invalid URL');return;}
      if(pathname==='/favicon.ico'){response.writeHead(204,headers);response.end();return;}
      const screenshot=/^\/screenshots\/(?:[a-zA-Z0-9_-]+\/)*[a-zA-Z0-9._-]+\.png$/.test(pathname);
      const relative=aliases[pathname]||(screenshot?pathname.slice(1):undefined);
      if(!relative){reject(404,'Not found');return;}
      let file,info;
      try{
        file=await fs.promises.realpath(path.resolve(root,relative));
        const within=path.relative(root,file);
        if(within==='..'||within.startsWith('..'+path.sep)||path.isAbsolute(within)){reject(404,'Not found');return;}
        info=await fs.promises.stat(file);
        if(!info.isFile()){reject(404,'Not found');return;}
      }catch{reject(404,'Not found');return;}
      const type=screenshot?'image/png':relative.endsWith('.json')?'application/json; charset=utf-8':relative.endsWith('.md')?'text/markdown; charset=utf-8':'text/html; charset=utf-8';
      response.writeHead(200,{...headers,'Content-Type':type,'Content-Length':info.size});
      if(request.method==='HEAD'){response.end();return;}
      fs.createReadStream(file).on('error',()=>response.destroy()).pipe(response);
    }
    handle().catch(()=>{if(!response.headersSent)reject(500,'Could not load demo resource');else response.destroy();});
  });
  return new Promise((resolve,reject)=>{server.once('error',reject);server.listen(port,host,()=>resolve(server));});
}
module.exports={servePreview};
if(require.main===module)servePreview(Number(process.argv[2]||4173),undefined,process.argv[3]||'127.0.0.1').then(server=>{
  const address=server.address();
  const host=address.address.includes(':')?'['+address.address+']':address.address;
  console.log('Atlas Payments simulated demo: http://'+host+':'+address.port);
}).catch(error=>{console.error(error.message);process.exitCode=1;});
