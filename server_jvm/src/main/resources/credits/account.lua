-- One account key; all validation precedes the single final HSET publication.
local key, user, mode = KEYS[1], ARGV[1], ARGV[2]
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
local function object(raw, fields)
  if not raw then bad() end
  local ok, value = pcall(cjson.decode, raw)
  if not ok or type(value) ~= 'table' then bad() end
  local count = 0
  for _ in pairs(value) do count = count + 1 end
  if count ~= fields then bad() end
  return value
end
local function uuid(s)
  return type(s) == 'string' and #s == 36 and s:sub(9,9) == '-' and s:sub(14,14) == '-'
    and s:sub(19,19) == '-' and s:sub(24,24) == '-'
    and #s:gsub('-', '') == 32 and s:gsub('-', ''):match('^[0-9a-f]+$') ~= nil
end
local function uid(s)
  return type(s) == 'string' and #s >= 4 and #s <= 9 and s:match('^[1-9a-z][0-9a-z]*$') ~= nil
end
local function transaction(raw, sequence)
  local ok, parsed = pcall(cjson.decode, raw)
  if not ok or type(parsed) ~= 'table' then bad() end
  local t = object(raw, parsed.business and 10 or 9)
  if t.sequence ~= sequence or not integer(t.sequence, 1, MAX) or t.userId ~= user
    or not uuid(t.requestId) or not uid(t.actorUserId)
    or not integer(t.delta, -MAX, MAX) or t.delta == 0
    or not integer(t.balanceBefore, 0, MAX) or not integer(t.balanceAfter, 0, MAX)
    or (t.delta > 0 and t.balanceBefore > MAX - t.delta)
    or (t.delta < 0 and t.balanceBefore < -t.delta)
    or t.balanceBefore + t.delta ~= t.balanceAfter
    or type(t.reason) ~= 'string' or #t.reason == 0
    or not integer(t.createdAtMillis, 0, MAX) then bad() end
  if t.business then
    local b = t.business
    if type(b) ~= 'table' then bad() end
    local fields = 0
    for _ in pairs(b) do fields = fields + 1 end
    if fields ~= 5 or type(b.source) ~= 'string' or #b.source > 64 or not b.source:match('^[a-z][a-z0-9-]*$')
      or type(b.taskId) ~= 'string' or #b.taskId == 0 or #b.taskId > 256
      or type(b.checkId) ~= 'string' or #b.checkId == 0 or #b.checkId > 128
      or not integer(b.unitPrice, 1, MAX) or not integer(b.quantity, 1, math.floor(MAX / b.unitPrice))
      or t.delta ~= -(b.unitPrice * b.quantity) then bad() end
    if redis.call('HGET', key, 'business:' .. b.source .. ':' .. b.taskId) ~= t.requestId then bad() end
  end
  return t
end
local function receipt(raw, sequence)
  local ok, r = pcall(cjson.decode, raw)
  if not ok or type(r) ~= 'table' or type(r.fingerprint) ~= 'string'
    or #r.fingerprint ~= 64 or not r.fingerprint:match('^[0-9a-f]+$') then bad() end
  if r.status == 'applied' then
    object(raw, 3)
    if not integer(r.sequence, 1, sequence) then bad() end
  elseif r.status == 'rejected' then
    object(raw, 4)
    if not integer(r.balance, 0, MAX) or (r.rejectionCode ~= 'insufficient_balance'
      and r.rejectionCode ~= 'balance_limit' and r.rejectionCode ~= 'sequence_limit') then bad() end
  else bad() end
  return r
end
local kind = redis.call('TYPE', key).ok
if kind ~= 'none' and kind ~= 'hash' then bad() end
local exists = kind == 'hash'
local balance, sequence, updated = 0, 0, 0
if exists then
  local meta = redis.call('HMGET', key, 'balance', 'sequence', 'updatedAtMillis')
  balance, sequence, updated = number(meta[1]), number(meta[2]), number(meta[3])
  if sequence == 0 then
    if balance ~= 0 or updated ~= 0 then bad() end
  else
    local t = transaction(redis.call('HGET', key, 'transaction:' .. fmt(sequence)), sequence)
    if t.balanceAfter ~= balance or t.createdAtMillis ~= updated then bad() end
    local r = receipt(redis.call('HGET', key, 'request:' .. t.requestId), sequence)
    if r.status ~= 'applied' or r.sequence ~= sequence then bad() end
  end
end
local result = {exists and '1' or '0', fmt(balance), fmt(sequence), fmt(updated)}
if mode == 'account' then return result end
if mode == 'page' then
  local before, limit = tonumber(ARGV[3]), tonumber(ARGV[4])
  if not integer(before, 0, MAX) or not integer(limit, 1, 100) then error('Invalid credit page') end
  local start = before == 0 and sequence or math.min(before, sequence)
  result[#result+1] = fmt(start)
  local previous
  for i = start, math.max(1, start - limit + 1), -1 do
    local raw = redis.call('HGET', key, 'transaction:' .. fmt(i))
    local t = transaction(raw, i)
    local r = receipt(redis.call('HGET', key, 'request:' .. t.requestId), sequence)
    if r.status ~= 'applied' or r.sequence ~= i or (previous and previous.balanceBefore ~= t.balanceAfter) then bad() end
    result[#result+1] = raw
    previous = t
  end
  return result
end
local request = ARGV[3]
if not uuid(request) then error('Invalid credit request identity') end
local old = redis.call('HGET', key, 'request:' .. request)
if old then
  local r = receipt(old, sequence)
  if (mode == 'adjust' or mode == 'debit') and r.fingerprint ~= ARGV[4] then return {'conflict'} end
  result[#result+1] = old
  if r.status == 'applied' then
    local raw = redis.call('HGET', key, 'transaction:' .. fmt(r.sequence))
    local t = transaction(raw, r.sequence)
    if t.requestId ~= request then bad() end
    if (mode == 'adjust' or mode == 'debit') and (t.actorUserId ~= ARGV[5] or t.delta ~= tonumber(ARGV[6]) or t.reason ~= ARGV[7]) then bad() end
    result[#result+1] = raw
  end
  return result
end
if mode == 'receipt' then return result end
if mode ~= 'adjust' and mode ~= 'debit' then error('Invalid credit operation') end
local fingerprint, actor, delta, reason, now = ARGV[4], ARGV[5], tonumber(ARGV[6]), ARGV[7], tonumber(ARGV[8])
if #fingerprint ~= 64 or not fingerprint:match('^[0-9a-f]+$') or not uid(actor)
  or not integer(delta, -MAX, MAX) or delta == 0 or #reason == 0 or not integer(now, 0, MAX) then error('Invalid credit adjustment') end
local businessKey, businessJson
if mode == 'debit' then
  local source, task, check, price, quantity = ARGV[9], ARGV[10], ARGV[11], tonumber(ARGV[12]), tonumber(ARGV[13])
  if not source or not source:match('^[a-z][a-z0-9-]*$') or not task or #task == 0 or not check or #check == 0
    or not integer(price, 1, MAX) or not integer(quantity, 1, math.floor(MAX / price))
    or delta ~= -(price * quantity) then error('Invalid business debit') end
  businessKey = 'business:' .. source .. ':' .. task
  local linked = redis.call('HGET', key, businessKey)
  if linked then
    if not uuid(linked) then bad() end
    local r = receipt(redis.call('HGET', key, 'request:' .. linked), sequence)
    if r.status ~= 'applied' then bad() end
    local t = transaction(redis.call('HGET', key, 'transaction:' .. fmt(r.sequence)), r.sequence)
    if not t.business or t.business.source ~= source or t.business.taskId ~= task or t.requestId ~= linked then bad() end
    return {'conflict'}
  end
  businessJson = '{"source":' .. cjson.encode(source) .. ',"taskId":' .. cjson.encode(task)
    .. ',"checkId":' .. cjson.encode(check) .. ',"unitPrice":' .. fmt(price) .. ',"quantity":' .. fmt(quantity) .. '}'
end
local rejected
if delta < 0 and balance < -delta then rejected = 'insufficient_balance'
elseif delta > 0 and balance > MAX - delta then rejected = 'balance_limit'
elseif sequence == MAX then rejected = 'sequence_limit' end
local quote = cjson.encode
local stored
if rejected then
  stored = '{"fingerprint":' .. quote(fingerprint) .. ',"status":"rejected","rejectionCode":' .. quote(rejected) .. ',"balance":' .. fmt(balance) .. '}'
  redis.call('HSET', key, 'balance', fmt(balance), 'sequence', fmt(sequence), 'updatedAtMillis', fmt(updated), 'request:' .. request, stored)
  result[1] = '1'
  result[#result+1] = stored
  return result
end
local next, after = sequence + 1, balance + delta
if redis.call('HEXISTS', key, 'transaction:' .. fmt(next)) == 1 then bad() end
-- cjson encodes numbers with insufficient precision near MAX; format safe integers explicitly.
local row = '{"sequence":' .. fmt(next) .. ',"requestId":' .. quote(request) .. ',"userId":' .. quote(user)
  .. ',"actorUserId":' .. quote(actor) .. ',"delta":' .. fmt(delta) .. ',"balanceBefore":' .. fmt(balance)
  .. ',"balanceAfter":' .. fmt(after) .. ',"reason":' .. quote(reason) .. ',"createdAtMillis":' .. fmt(now) .. '}'
if businessJson then row = row:sub(1, -2) .. ',"business":' .. businessJson .. '}' end
stored = '{"fingerprint":' .. quote(fingerprint) .. ',"status":"applied","sequence":' .. fmt(next) .. '}'
local fields = {'balance', fmt(after), 'sequence', fmt(next), 'updatedAtMillis', fmt(now),
  'transaction:' .. fmt(next), row, 'request:' .. request, stored}
if businessKey then fields[#fields+1] = businessKey; fields[#fields+1] = request end
redis.call('HSET', key, unpack(fields))
return {'1', fmt(after), fmt(next), fmt(now), stored, row}
