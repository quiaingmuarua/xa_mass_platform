package com.xa.mass.workermatching.rules;

/** Shared by the existing ZSET Rules, not a requirement on other Rule layouts. */
final class ZsetProjection {
    private ZsetProjection() { }
    static String prepare(String derive) {
        return PartitionedZsetIndex.PARTITIONS_LUA + """
            local scale=8796093022208
            local function country(value)
              if type(value)~='string' or not string.match(value,'^[A-Z][A-Z]$') then return -1 end
              return (string.byte(value,1)-65)*26+string.byte(value,2)-65
            end
            local function derive(w,p)
            """ + derive + "\nend\n" + """
            return function(key,id,w,p)
              local code,parts=derive(w,p)
              local raw=redis.call('ZSCORE',key,id)
              local prior=raw and tonumber(raw) or 0
              if not prior or prior<0 or prior>=676*scale or prior~=math.floor(prior) then error('corrupt Rule index') end
              local old=partitions(key,id)
              checkPartitionKeys(key,old); checkPartitionKeys(key,parts)
              local low=prior%scale
              return function()
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
    }
}
