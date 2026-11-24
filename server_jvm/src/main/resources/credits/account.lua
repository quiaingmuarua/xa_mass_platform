-- Atomic single-key credit write: preflight metadata and head invariants, then one final HSET.
local key, user, request = KEYS[1], ARGV[1], ARGV[2]
local delta, now, businessKey = tonumber(ARGV[3]), tonumber(ARGV[4]), ARGV[5]
local MAX = 9007199254740991
local function bad() error('Credit account storage is corrupt') end
local function integer(n, low, high)
  return type(n) == 'number' and n >= low and n <= high and n == math.floor(n)
end
local function fmt(n) return string.format('%.0f', n) end
local function number(s)
  if type(s) ~= 'string' then bad() end
  local n = tonumber(s)
  if not integer(n, 0, MAX) or fmt(n) ~= s then bad() end
  return n
end
local function decode(raw)
  if type(raw) ~= 'string' then bad() end
  local ok, value = pcall(cjson.decode, raw)
  if not ok or type(value) ~= 'table' then bad() end
  return value
end

local kind = redis.call('TYPE', key).ok
if kind ~= 'none' and kind ~= 'hash' then bad() end
local balance, sequence, updated = 0, 0, 0
if kind == 'hash' then
  local meta = redis.call('HMGET', key, 'balance', 'sequence', 'updatedAtMillis')
  balance, sequence, updated = number(meta[1]), number(meta[2]), number(meta[3])
  if sequence == 0 then
    if balance ~= 0 or updated ~= 0 then bad() end
  else
    local t = decode(redis.call('HGET', key, 'transaction:' .. fmt(sequence)))
    if t.sequence ~= sequence or t.userId ~= user or t.balanceAfter ~= balance
        or t.createdAtMillis ~= updated or type(t.requestId) ~= 'string' then bad() end
    local r = decode(redis.call('HGET', key, 'request:' .. t.requestId))
    if r.status ~= 'applied' or r.sequence ~= sequence then bad() end
    if t.business ~= nil then
      if type(t.business) ~= 'table' or type(t.business.source) ~= 'string'
          or type(t.business.taskId) ~= 'string'
          or redis.call('HGET', key, 'business:' .. t.business.source .. ':' .. t.business.taskId) ~= t.requestId then
        bad()
      end
    end
  end
end

local old = redis.call('HGET', key, 'request:' .. request)
if old then
  local r = decode(old)
  local tx = ''
  if r.status == 'applied' then
    if not integer(r.sequence, 1, sequence) then bad() end
    tx = redis.call('HGET', key, 'transaction:' .. fmt(r.sequence))
    if not tx then bad() end
  end
  return {'existing', fmt(sequence), old, tx}
end

if businessKey ~= '' then
  local linked = redis.call('HGET', key, businessKey)
  if linked then
    return {'business_exists', fmt(sequence), linked}
  end
end

local rejected
if delta < 0 and balance < -delta then rejected = 'insufficient_balance'
elseif delta > 0 and balance > MAX - delta then rejected = 'balance_limit'
elseif sequence == MAX then rejected = 'sequence_limit' end
if rejected then
  local stored = ARGV[8] .. rejected .. '","balance":' .. fmt(balance) .. '}'
  redis.call('HSET', key, 'balance', fmt(balance), 'sequence', fmt(sequence),
      'updatedAtMillis', fmt(updated), 'request:' .. request, stored)
  return {'committed', stored, ''}
end

local next, after = sequence + 1, balance + delta
if redis.call('HEXISTS', key, 'transaction:' .. fmt(next)) == 1 then bad() end
local row = '{"sequence":' .. fmt(next) .. ',"balanceBefore":' .. fmt(balance)
    .. ',"balanceAfter":' .. fmt(after) .. ARGV[6]
local stored = ARGV[7] .. fmt(next) .. '}'
local fields = {'balance', fmt(after), 'sequence', fmt(next), 'updatedAtMillis', fmt(now),
    'transaction:' .. fmt(next), row, 'request:' .. request, stored}
if businessKey ~= '' then fields[#fields + 1] = businessKey; fields[#fields + 1] = request end
redis.call('HSET', key, unpack(fields))
return {'committed', stored, row}
