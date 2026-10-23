export function createPageCache(limit=16) {
  const ready=new Map(),pending=new Map()
  return {
    peek:key=>ready.get(key),
    load(key,fetcher){
      if(ready.has(key))return Promise.resolve(ready.get(key))
      if(pending.has(key))return pending.get(key)
      const task=Promise.resolve().then(fetcher).then(value=>{ready.set(key,value);while(ready.size>limit)ready.delete(ready.keys().next().value);return value}).finally(()=>pending.delete(key))
      pending.set(key,task);return task
    }
  }
}
