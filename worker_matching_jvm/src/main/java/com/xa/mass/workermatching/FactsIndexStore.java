package com.xa.mass.workermatching;

import java.util.List;

/** Facts and all enabled indexes prepare before the first write, in one bounded Lua operation. */
final class FactsIndexStore {
    private FactsIndexStore() { }
    private static final String HELPERS = """
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
            """;

    static String script(List<com.xa.mass.workermatching.rules.MatchingStorage.IndexMutation> indexes) {
        var source=new StringBuilder(HELPERS);
        for (int i=0;i<indexes.size();i++) source.append("local prepare_").append(i)
                .append("=(function()\n").append(indexes.get(i).prepareLua()).append("\nend)()\n");
        source.append("""
                local results, writes, updates={},{},{}
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
        for (int i=0;i<indexes.size();i++) source.append("local apply=prepare_").append(i)
                .append("(KEYS[3]..':").append(indexes.get(i).namespace()).append("',id,w,p)\nif type(apply)~='function' then error('invalid Rule update') end\nupdates[#updates+1]=apply\n");
        source.append("""
                  end
                end
                for _,w in ipairs(writes) do redis.call('HSET',unpack(w)) end
                for _,apply in ipairs(updates) do apply() end
                return results
                """);
        return source.toString();
    }
}
