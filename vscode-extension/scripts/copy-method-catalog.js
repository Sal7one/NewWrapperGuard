const fs = require('node:fs');
const path = require('node:path');
const target = path.join(__dirname,'../dist/methods');
fs.mkdirSync(target,{recursive:true});
fs.copyFileSync(path.join(__dirname,'../../catalog/security-methods.json'),path.join(target,'security-methods.json'));
