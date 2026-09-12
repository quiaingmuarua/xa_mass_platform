package com.xa.mass.workermatching;

import java.util.Set;

/** Compiles the finite Group projections into the same bounded facts mutation. */
final class FactsIndexStore {
    private FactsIndexStore() { }
    private static final String HELPERS = RuleIndex.PARTITIONS_LUA + """
            local scale=8796093022208
            local function country(value)
              if type(value)~='string' or not string.match(value,'^[A-Z][A-Z]$') then return -1 end
              return (string.byte(value,1)-65)*26+string.byte(value,2)-65
            end
            local function object(raw)
              if not raw or not string.match(raw,'^%s*{') then error('corrupt Matching facts') end
              local value=cjson.decode(raw)
              if type(value)~='table' then error('corrupt Matching facts') end
              return value
            end
            -- Preserve nested JSON shapes, including empty arrays, while merging only top-level fields.
            local function fields(raw)
              object(raw)
              local result, i = {}, string.find(raw,'{',1,true)+1
              while i <= #raw do
                while string.match(string.sub(raw,i,i),'[%s,]') do i=i+1 end
                if string.sub(raw,i,i)=='}' then break end
                local start=i; i=i+1
                while i<=#raw do
                  local c=string.sub(raw,i,i)
                  if c=='\\\\' then i=i+2 elseif c=='"' then i=i+1; break else i=i+1 end
                end
                local name=cjson.decode(string.sub(raw,start,i-1))
                while string.match(string.sub(raw,i,i),'[%s:]') do i=i+1 end
                start=i
                local depth,quoted=0,false
                while i<=#raw do
                  local c=string.sub(raw,i,i)
                  if quoted then
                    if c=='\\\\' then i=i+1 elseif c=='"' then quoted=false end
                  elseif c=='"' then quoted=true
                  elseif c=='{' or c=='[' then depth=depth+1
                  elseif c=='}' or c==']' then if depth==0 then break end; depth=depth-1
                  elseif c==',' and depth==0 then break end
                  i=i+1
                end
                result[name]=string.match(string.sub(raw,start,i-1),'^%s*(.-)%s*$')
              end
              return result
            end
            local function patch(current,delta)
              local values=fields(current)
              for name,raw in pairs(fields(delta)) do values[name]=raw~='null' and raw or nil end
              local names={}; for name in pairs(values) do names[#names+1]=name end; table.sort(names)
              local encoded={}; for _,name in ipairs(names) do encoded[#encoded+1]=cjson.encode(name)..':'..values[name] end
              return '{'..table.concat(encoded,',')..'}'
            end
            local updates={}
            local function project(key,id,code,parts)
              local raw=redis.call('ZSCORE',key,id)
              local prior=raw and tonumber(raw) or 0
              if not prior or prior<0 or prior>=676*scale or prior~=math.floor(prior) then error('corrupt Rule index') end
              local old=partitions(key,id)
              checkPartitionKeys(key,old); checkPartitionKeys(key,parts)
              updates[#updates+1]={key,id,code,parts,old,prior%scale}
            end
            local function commit()
              for _,u in ipairs(updates) do
                local key,id,code,parts,old,low=unpack(u)
                for _,suffix in ipairs(old) do redis.call('ZREM',key..':partition:'..redis.sha1hex(suffix),id) end
                if code<0 then
                  redis.call('ZREM',key,id); redis.call('HDEL',key..':partitions',id)
                else
                  local score=string.format('%.0f',code*scale+low)
                  redis.call('ZADD',key,score,id)
                  if #parts>0 then redis.call('HSET',key..':partitions',id,cjson.encode(parts))
                  else redis.call('HDEL',key..':partitions',id) end
                  for _,suffix in ipairs(parts) do redis.call('ZADD',key..':partition:'..redis.sha1hex(suffix),score,id) end
                end
              end
            end
            """;

    static String script(Set<RuleHandler> handlers) {
        var source=new StringBuilder(HELPERS);
        for (var handler : handlers) source.append("local function derive_").append(handler.indexName)
                .append("(w,p)\n").append(handler.projection).append("end\n");
        source.append("""
                local results, writes={},{}
                local mode=ARGV[1]
                for i=2,#ARGV,2 do
                  local id,input=ARGV[i],ARGV[i+1]
                  local old=redis.call('HGET',KEYS[1],id)
                  local platform=redis.call('HGET',KEYS[2],id) or '{}'
                  if mode=='patch' and not old then results[#results+1]=-1
                  else
                    if old then object(old) end
                    local replacement=mode=='replace' and input or old
                    local nextPlatform=mode=='patch' and patch(platform,input) or platform
                    local w,p=object(replacement),object(nextPlatform)
                    local effect=(mode=='replace' and old~=replacement or mode=='patch' and platform~=nextPlatform) and 1 or 0
                    results[#results+1]=effect
                    if effect==1 then writes[#writes+1]={mode=='patch' and KEYS[2] or KEYS[1],id,mode=='patch' and nextPlatform or replacement} end
                """);
        for (var handler : handlers) source.append("local code,parts=derive_").append(handler.indexName)
                .append("(w,p)\nproject(KEYS[3]..':").append(handler.indexName).append("',id,code,parts)\n");
        source.append("""
                  end
                end
                for _,w in ipairs(writes) do redis.call('HSET',unpack(w)) end
                commit()
                return results
                """);
        return source.toString();
    }
}
