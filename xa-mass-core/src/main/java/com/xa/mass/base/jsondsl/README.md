# JSON-DSL 妗嗘灦

涓€涓熀浜?JSON-DSL 鐨勯€氱敤 Java 瀵硅薄鐢熸垚妗嗘灦锛屾敮鎸佹壒閲忕敓鎴愪换鎰忓璞°€侀€掑綊宓屽銆佸唴缃嚱鏁板拰绫诲瀷娉ㄥ唽琛ㄣ€?

## 鐗规€?

- 馃殌 **閫氱敤鎬?*: 鏀寔鐢熸垚浠绘剰 Java 瀵硅薄
- 馃摑 **DSL 椹卞姩**: 浣跨敤绠€娲佺殑 JSON-DSL 璇硶瀹氫箟鐢熸垚瑙勫垯
- 馃攧 **閫掑綊宓屽**: 鏀寔澶嶆潅鐨勫祵濂楀璞″拰闆嗗悎缁撴瀯
- 馃洜锔?**鍐呯疆鍑芥暟**: 鎻愪緵涓板瘜鐨勫唴缃嚱鏁帮紙闅忔満閫夋嫨銆佽寖鍥淬€乁UID銆佹椂闂寸瓑锛?
- 馃搵 **绫诲瀷娉ㄥ唽**: 鏀寔绫诲瀷鍒悕娉ㄥ唽锛岄伩鍏嶇‖缂栫爜鍏ㄧ被鍚?
- 馃幆 **绫诲瀷瀹夊叏**: 閫氳繃鍙傛暟鎺у埗杩斿洖绫诲瀷锛屾彁渚涙槑纭殑绫诲瀷淇濊瘉
- 馃敡 **澶氱骇浣滅敤鍩熷彉閲?*: 鏀寔 `&.index`锛堝綋鍓嶄綔鐢ㄥ煙绱㈠紩绠€鍐欙級鍜?`&Model.index`锛岃嚜鍔ㄩ€掑綊鏌ユ壘鐖朵綔鐢ㄥ煙
- 鈴?**鏃堕棿鏀寔**: 鏀寔褰撳墠鏃堕棿鍜屾椂闂磋寖鍥撮殢鏈虹敓鎴?
- 馃敡 **鍙墿灞?*: 鍐呯疆鍑芥暟鍜岀被鍨嬫敞鍐岃〃鏀寔鍔ㄦ€佹墿灞?

## 蹇€熷紑濮?

### 1. 娉ㄥ唽绫诲瀷

```java
// 娉ㄥ唽绫诲瀷鍒悕
TypeRegistry.register("Device", Device.class);
TypeRegistry.register("Task", Task.class);
```

### 2. 瀹氫箟 DSL

```json
{
  "MODEL": "Device",
  "COUNT": 3,
  "FIELDS": {
    "deviceId": {"$JOIN": ["device-", "&.index"]},
    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]},
    "deviceGroupId": {"$CHOICE": ["us", "gb", "cn"]},
    "agentVersion": {"$JOIN": ["1.0.", "&.index"]}
  }
}
```

### 3. 鐢熸垚鏁版嵁

```java
String deviceDsl = "..."; // 涓婇潰鐨?JSON

// 榛樿杩斿洖鍒楄〃锛堟帹鑽愶級
List<Object> devices = JsonDslEngine.generate(deviceDsl);
devices.

forEach(System.out::println);

// 鎸囧畾杩斿洖绫诲瀷
Object singleDevice = JsonDslEngine.generate(deviceDsl, JsonDslEngine.ReturnType.SINGLE);
List<Object> deviceList = JsonDslEngine.generate(deviceDsl, JsonDslEngine.ReturnType.LIST);
Map<String, Object> deviceMap = JsonDslEngine.generate(deviceDsl, JsonDslEngine.ReturnType.MAP);
```

## API 璁捐

### 杩斿洖绫诲瀷鏋氫妇

```java
public enum ReturnType {
    AUTO,    // 鑷姩鍒ゆ柇锛氬崟涓璞¤繑鍥?Object锛屽涓璞¤繑鍥?List锛屽涓ā鍨嬭繑鍥?Map
    SINGLE,  // 寮哄埗杩斿洖鍗曚釜瀵硅薄
    LIST,    // 寮哄埗杩斿洖瀵硅薄鍒楄〃锛堥粯璁わ級
    MAP      // 寮哄埗杩斿洖妯″瀷鏄犲皠
}
```

### 鏍稿績鏂规硶

#### `generate(String jsonDsl)` - 榛樿杩斿洖鍒楄〃

```java
// 榛樿杩斿洖 List<Object>锛屽嵆浣?DSL 鍙畾涔変簡涓€涓璞′篃浼氬寘瑁呬负鍒楄〃
List<Object> devices = JsonDslEngine.generate(deviceDsl);
```

#### `generate(String jsonDsl, ReturnType returnType)` - 鎸囧畾杩斿洖绫诲瀷

```java
// 杩斿洖鍗曚釜瀵硅薄
Object device = JsonDslEngine.generate(deviceDsl, JsonDslEngine.ReturnType.SINGLE);

// 杩斿洖鍒楄〃锛堜笌榛樿鏂规硶鐩稿悓锛?
List<Object> devices = JsonDslEngine.generate(deviceDsl, JsonDslEngine.ReturnType.LIST);

// 杩斿洖鏄犲皠
Map<String, Object> models = JsonDslEngine.generate(modelsDsl, JsonDslEngine.ReturnType.MAP);

// 鑷姩鍒ゆ柇锛堟牴鎹?DSL 缁撴瀯锛?
Object result = JsonDslEngine.generate(deviceDsl, JsonDslEngine.ReturnType.AUTO);
```

### 渚垮埄鏂规硶

#### `generateSingle(String jsonDsl)`

寮哄埗杩斿洖鍗曚釜瀵硅薄锛?

```java
Object device = JsonDslEngine.generateSingle(deviceDsl);
```

#### `generateList(String jsonDsl)`

寮哄埗杩斿洖瀵硅薄鍒楄〃锛?

```java
List<Object> devices = JsonDslEngine.generateList(deviceDsl);
```

#### `generateMap(String jsonDsl, String modelName)`

寮哄埗杩斿洖妯″瀷鏄犲皠锛?

```java
Map<String, Object> models = JsonDslEngine.generateMap(deviceDsl, "Device");
```

#### `generateTyped(String jsonDsl, Class<T> targetType)`

甯︾被鍨嬭浆鎹㈢殑鐢熸垚鏂规硶锛?

```java
List<Object> devices = JsonDslEngine.generateTyped(deviceDsl, List.class);
Map<String, Object> models = JsonDslEngine.generateTyped(modelsDsl, Map.class);
```

## 浣跨敤绀轰緥

### 绀轰緥1锛氶粯璁よ繑鍥炲垪琛紙鎺ㄨ崘锛?

```java
String singleDeviceDsl = """
        {
            "MODEL": "Device",
            "FIELDS": {
                "deviceId": "device-001",
                "status": "ONLINE"
            }
        }
        """;

// 榛樿杩斿洖 List<Object>锛屽嵆浣垮彧鏈変竴涓璞?
List<Object> devices = JsonDslEngine.generate(singleDeviceDsl);
System.out.

println("鐢熸垚浜?"+devices.size() +" 涓澶?);
```

### 绀轰緥2锛氭寚瀹氳繑鍥炲崟涓璞?

```java
String deviceDsl = """
        {
            "MODEL": "Device",
            "COUNT": 3,
            "FIELDS": {
                "deviceId": {"$JOIN": ["device-", "&.index"]},
                "status": {"$CHOICE": ["ONLINE", "OFFLINE"]}
            }
        }
        """;

// 杩斿洖绗竴涓璞?
Object device = JsonDslEngine.generate(deviceDsl, JsonDslEngine.ReturnType.SINGLE);
System.out.

println("绗竴涓澶? "+device);
```

### 绀轰緥3锛氭寚瀹氳繑鍥炲垪琛?

```java
String singleDsl = """
        {
            "MODEL": "Device",
            "FIELDS": {
                "deviceId": "device-001",
                "status": "ONLINE"
            }
        }
        """;

// 寮哄埗杩斿洖鍒楄〃锛屽崟涓璞′細琚寘瑁?
List<Object> devices = JsonDslEngine.generate(singleDsl, JsonDslEngine.ReturnType.LIST);
System.out.

println("鍒楄〃澶у皬: "+devices.size()); // 杈撳嚭: 1
```

### 绀轰緥4锛氭寚瀹氳繑鍥炴槧灏?

```java
String deviceDsl = """
        {
            "MODEL": "Device",
            "FIELDS": {
                "deviceId": "device-001",
                "status": "ONLINE"
            }
        }
        """;

// 寮哄埗杩斿洖鏄犲皠锛屽崟涓璞′細琚寘瑁?
Map<String, Object> models = JsonDslEngine.generate(deviceDsl, JsonDslEngine.ReturnType.MAP);
System.out.

println("鏄犲皠閿? "+models.keySet()); // 杈撳嚭: [result]
```

### 绀轰緥5锛氬涓ā鍨?

```java
String multipleModelsDsl = """
        {
            "device": {
                "MODEL": "Device",
                "FIELDS": {
                    "deviceId": "device-001",
                    "status": "ONLINE"
                }
            },
            "task": {
                "MODEL": "Task",
                "FIELDS": {
                    "taskId": "task-001",
                    "priority": "HIGH"
                }
            }
        }
        """;

// 杩斿洖 Map<String, Object>
Map<String, Object> models = JsonDslEngine.generate(multipleModelsDsl, JsonDslEngine.ReturnType.MAP);
models.

forEach((key, value) ->{
        System.out.

println("妯″瀷 "+key +": "+value.getClass().

getSimpleName());
        });
```

### 绀轰緥6锛氫娇鐢ㄤ究鍒╂柟娉?

```java
String deviceDsl = "..."; // 鍖呭惈 COUNT: 2 鐨?DSL

// 寮哄埗鑾峰彇鍗曚釜瀵硅薄
Object single = JsonDslEngine.generateSingle(deviceDsl);

// 寮哄埗鑾峰彇鍒楄〃
List<Object> list = JsonDslEngine.generateList(deviceDsl);

// 寮哄埗鑾峰彇鏄犲皠
Map<String, Object> map = JsonDslEngine.generateMap(deviceDsl, "Device");
```

## 杩斿洖绫诲瀷璇存槑

| ReturnType  | 璇存槑                         | 绀轰緥                                   |
|-------------|----------------------------|--------------------------------------|
| `LIST` (榛樿) | 鎬绘槸杩斿洖 `List<Object>`        | 鍗曚釜瀵硅薄鍖呰涓哄崟鍏冪礌鍒楄〃                         |
| `SINGLE`    | 鎬绘槸杩斿洖 `Object`              | 澶氫釜瀵硅薄鏃惰繑鍥炵涓€涓?                          |
| `MAP`       | 鎬绘槸杩斿洖 `Map<String, Object>` | 鍗曚釜瀵硅薄鍖呰涓?`{"result": object}`         |
| `AUTO`      | 鏍规嵁 DSL 缁撴瀯鑷姩鍒ゆ柇              | 鍗曚釜瀵硅薄杩斿洖 Object锛屽涓璞¤繑鍥?List锛屽涓ā鍨嬭繑鍥?Map |

## 鏈€浣冲疄璺?

1. **鎺ㄨ崘浣跨敤榛樿鏂规硶**: `JsonDslEngine.generate(dsl)` 鎬绘槸杩斿洖鍒楄〃锛岀被鍨嬪畨鍏ㄤ笖涓€鑷?
2. **鏄庣‘鎸囧畾杩斿洖绫诲瀷**: 褰撻渶瑕佺壒瀹氱被鍨嬫椂锛屼娇鐢?`ReturnType` 鍙傛暟
3. **浣跨敤渚垮埄鏂规硶**: 瀵逛簬甯歌鍦烘櫙锛屼娇鐢?`generateSingle()`, `generateList()`, `generateMap()`
4. **绫诲瀷妫€鏌?*: 浣跨敤 `instanceof` 妫€鏌ヨ繑鍥炵被鍨嬶紝鐗瑰埆鏄湪浣跨敤 `AUTO` 妯″紡鏃?

## DSL 璇硶

### 鏍稿績鍏抽敭瀛?

| 鍏抽敭瀛?     | 绫诲瀷     | 璇存槑             |
|----------|--------|----------------|
| `MODEL`  | String | 鎸囧畾瑕佺敓鎴愮殑妯″瀷绫诲悕锛堝繀闇€锛?|
| `FIELDS` | Object | 瀛楁閰嶇疆鏄犲皠         |
| `COUNT`  | Number | 鐢熸垚鏁伴噺锛堥粯璁?锛?     |
| `TYPE`   | String | 闆嗗悎绫诲瀷锛圠IST/SET锛?|

### 鍐呯疆鍑芥暟

| 鍑芥暟            | 璇硶                                            | 璇存槑           | 绀轰緥                                                                      |
|---------------|-----------------------------------------------|--------------|-------------------------------------------------------------------------|
| `$CHOICE`     | `{"$CHOICE": [閫夐」鍒楄〃]}`                         | 浠庡垪琛ㄤ腑闅忔満閫夋嫨     | `{"$CHOICE": ["A", "B", "C"]}`                                          |
| `$RANGE`      | `{"$RANGE": [鏈€灏忓€? 鏈€澶у€糫}`                      | 鐢熸垚鎸囧畾鑼冨洿鍐呯殑闅忔満鏁? | `{"$RANGE": [1, 100]}`                                                  |
| `$UUID`       | `{"$UUID": true}`                             | 鐢熸垚 UUID      | `{"$UUID": true}`                                                       |
| `$RANDOM`     | `{"$RANDOM": true}`                           | 鐢熸垚闅忔満鏁存暟       | `{"$RANDOM": true}`                                                     |
| `$JOIN`       | `{"$JOIN": [瀛楃涓插垪琛╙}`                          | 瀛楃涓叉嫾鎺?       | `{"$JOIN": ["prefix-", "&.index", "-suffix"]}`                          |
| `$NOW`        | `{"$NOW": "鏍煎紡鍖栧瓧绗︿覆"}`                          | 鑾峰彇褰撳墠鏃堕棿       | `{"$NOW": "yyyy-MM-dd HH:mm:ss"}`                                       |
| `$TIME_RANGE` | `{"$TIME_RANGE": [寮€濮嬫椂闂? 缁撴潫鏃堕棿, 鏃堕棿鍗曚綅, 鏍煎紡鍖栧瓧绗︿覆]}` | 鍦ㄦ椂闂磋寖鍥村唴闅忔満鐢熸垚鏃堕棿 | `{"$TIME_RANGE": ["now-1d", "now+1d", "HOURS", "yyyy-MM-dd HH:mm:ss"]}` |

### 澶氱骇浣滅敤鍩熷彉閲忎笌绠€鍐?

- 浠?`&` 寮€澶寸殑瀛楃涓诧紙濡?`&.index`銆乣&Device.index`锛変細鑷姩鍦ㄥ綋鍓嶅強鐖朵綔鐢ㄥ煙閫掑綊鏌ユ壘
- `&.index` 琛ㄧず"褰撳墠浣滅敤鍩熺殑绱㈠紩"锛屾帹鑽愪紭鍏堜娇鐢?
- `&Model.index` 绮剧‘鎸囧悜鎸囧畾浣滅敤鍩熺殑绱㈠紩
- 鏀寔澶氬眰宓屽銆佺埗瀛愪綔鐢ㄥ煙闅旂
- 鎺ㄨ崘鎵€鏈夌储寮曘€佷綔鐢ㄥ煙鍙橀噺閮界敤 `&.index` 鎴?`&Model.index` 鏂瑰紡鍛藉悕

#### 鍙橀噺鏌ユ壘绀轰緥

```json
{
  "MODEL": "Device",
  "COUNT": 2,
  "FIELDS": {
    "deviceId": {"$JOIN": ["device-", "&.index"]},
    "tasks": {
      "TYPE": "LIST",
      "COUNT": 2,
      "MODEL": "Task",
      "FIELDS": {
        "taskName": {"$JOIN": ["Task-", "&.index", "-of-Device-", "&Device.index"]},
        "parentDeviceId": "&Device.deviceId"
      }
    }
  }
}
```

- `&.index`锛氭煡鎵惧綋鍓嶄綔鐢ㄥ煙鐨?index锛堝 Task 浣滅敤鍩熸椂涓?Task 鐨?index锛孌evice 浣滅敤鍩熸椂涓?Device 鐨?index锛?
- `&Device.index`锛氭煡鎵炬渶杩戠殑 Device 浣滅敤鍩熺殑 index
- `&Device.deviceId`锛氭煡鎵炬渶杩戠殑 Device 浣滅敤鍩熺殑 deviceId

### 鏃堕棿鍑芥暟绀轰緥

```json
{
  "MODEL": "Task",
  "COUNT": 3,
  "FIELDS": {
    "tid": {"$UUID": true},
    "taskName": {"$JOIN": ["TimeTask-", "&.index"]},
    "createdTime": {"$NOW": "yyyy-MM-dd HH:mm:ss"},
    "lastModified": {"$TIME_RANGE": ["now-2h", "now", "MINUTES"]}
  }
}
```

## 绫诲瀷娉ㄥ唽

### 娉ㄥ唽绫诲瀷鍒悕

```java
// 浣跨敤绫诲璞℃敞鍐?
TypeRegistry.register("Device",Device .class);
TypeRegistry.

register("Task",Task .class);

// 浣跨敤鍏ㄧ被鍚嶆敞鍐?
TypeRegistry.

register("RuleDefinition","com.xa.mass.engine.rules.RuleDefinition");
```

### 浣跨敤鍏ㄧ被鍚?

濡傛灉绫诲瀷鏈敞鍐岋紝鍙互鐩存帴浣跨敤鍏ㄧ被鍚嶏細

```json
{
  "MODEL": "com.xa.mass.base.model.Device",
  "FIELDS": {
    "deviceId": "device-001"
  }
}
```

## 閿欒澶勭悊

妗嗘灦浣跨敤 `JsonDslException` 缁熶竴澶勭悊閿欒锛?

```java
try{
List<Object> objects = JsonDslEngine.generate(dsl);
}catch(
JsonDslException e){
        System.err.

println("DSL 鐢熸垚澶辫触: "+e.getMessage());
        }
```

甯歌閿欒锛?

- DSL 缂哄皯 `MODEL` 瀛楁
- 绫诲瀷鏈敞鍐屾垨鏃犳硶鍔犺浇
- 瀛楁璁剧疆澶辫触
- 涓嶆敮鎸佺殑鍐呯疆鍑芥暟
- 鏃堕棿鏍煎紡瑙ｆ瀽澶辫触

## 鎵╁睍鎬?

### 娣诲姞鏂扮殑鍐呯疆鍑芥暟

1. 鍦?`BuiltinFunc` 鏋氫妇涓坊鍔犳柊鍑芥暟
2. 鍦?`BuiltinFunctions` 涓疄鐜板嚱鏁伴€昏緫
3. 鍦?`TemplateValueResolver` 涓敞鍐岃В鏋愬櫒

### 鑷畾涔夌被鍨嬭В鏋?

鍙互閫氳繃缁ф壙鎴栫粍鍚堢殑鏂瑰紡鎵╁睍绫诲瀷瑙ｆ瀽閫昏緫銆?

## 琛ㄨ揪寮忓紩鎿庝笌鍐呯疆鍑芥暟鍒悕

### QLExpress 琛ㄨ揪寮忔敮鎸?

- 鏀寔閫氳繃 `$EXPR` 瀛楁宓屽叆 QLExpress 琛ㄨ揪寮忥紝琛ㄨ揪寮忓彲寮曠敤褰撳墠涓婁笅鏂囧彉閲忓拰鎵€鏈夊唴缃嚱鏁般€?
- 鎵€鏈夊唴缃嚱鏁帮紙濡?random銆乧hoice銆乺ange銆乽uid銆乯oin銆乶ow銆乼imeRange 绛夛級鍧囨敮鎸佸绉嶅埆鍚嶏紙濡?
  random/rand銆乼imeRange/timerange锛夛紝鍙湪琛ㄨ揪寮忎腑鐩存帴璋冪敤銆?
- 鍐呯疆鍑芥暟娉ㄥ唽閲囩敤闆嗕腑鑷姩娉ㄥ唽鏈哄埗锛屾墍鏈夊埆鍚嶅拰瀹炵幇缁熶竴缁存姢浜?BuiltinFunc 鍜?BuiltinFunctions锛屾棤闇€鎵嬪姩娉ㄥ唽銆?

#### 绀轰緥锛氬姩鎬?mock 瀛楁渚濊禆琛ㄨ揪寮?

```json
{
  "MODEL": "Device",
  "FIELDS": {
    "status": {"$CHOICE": ["OFFLINE", "ONLINE"]},
    "onlineStrategy": {
      "$EXPR": {
        "lang": "ql",
        "expr": "status == 'OFFLINE' ? 0 : range(10, 100)"
      }
    },
    "randValue": {"$EXPR": {"lang": "ql", "expr": "random(1, 10)"}},
    "timeStr": {"$EXPR": {"lang": "ql", "expr": "now('yyyy-MM-dd HH:mm')"}},
    "timeRange1": {"$EXPR": {"lang": "ql", "expr": "timeRange('now-1d', 'now', 'HOURS', 'yyyy-MM-dd HH:mm')"}},
    "timeRange2": {"$EXPR": {"lang": "ql", "expr": "timerange('now-1d', 'now', 'HOURS', 'yyyy-MM-dd HH:mm')"}}
  }
}
```

- 鏀寔琛ㄨ揪寮忓唴浠绘剰缁勫悎鍐呯疆鍑芥暟銆佷笂涓嬫枃鍙橀噺銆佷笁鍏冭〃杈惧紡绛夈€?
- 鎵€鏈夊唴缃嚱鏁板埆鍚嶏紙濡?random/rand銆乼imeRange/timerange锛夊潎鍙洿鎺ュ湪琛ㄨ揪寮忎腑璋冪敤銆?
- 琛ㄨ揪寮忓彉閲忚嚜鍔ㄦ敞鍏ワ紝鏃犻渶鎵嬪姩澹版槑銆?

### 鍐呯疆鍑芥暟鍒悕涓庤嚜鍔ㄦ敞鍐屾満鍒?

- BuiltinFunc 鏋氫妇鏀寔涓烘瘡涓唴缃嚱鏁伴厤缃涓埆鍚嶃€?
- BuiltinFunctions.registerToQLExpress 浼氳嚜鍔ㄩ亶鍘嗘墍鏈夊埆鍚嶆壒閲忔敞鍐岋紝鏃犻渶鎵嬪姩缁存姢娉ㄥ唽浠ｇ爜銆?
- 鏂板鍐呯疆鍑芥暟鏃讹紝鍙渶鍦?BuiltinFunc 鍜?FUNCTION_MAP 涓ˉ鍏呭嵆鍙紝娉ㄥ唽鍜屽埆鍚嶈嚜鍔ㄧ敓鏁堛€?

### $EXPR 璇硶绯栨敮鎸?

- 鏀寔鐩存帴鍐欏瓧绗︿覆浣滀负琛ㄨ揪寮忥紝绛変环浜?`{lang: 'ql', expr: ...}`锛屾棤闇€鍐椾綑瀵硅薄鍖呰９銆?
- 鎺ㄨ崘鍐欐硶锛?

```json
{
  "MODEL": "Device",
  "FIELDS": {
    "randValue": {"$EXPR": "random(1, 10)"},
    "status": {"$EXPR": "choice(['ONLINE','OFFLINE'])"}
  }
}
```

- 鍏煎鍘熸湁瀵硅薄鍐欐硶锛?

```json
{
  "randValue": {"$EXPR": {"lang": "ql", "expr": "random(1, 10)"}}
}
```

- 缁濆ぇ澶氭暟鍦烘櫙鎺ㄨ崘鐩存帴鐢ㄥ瓧绗︿覆鍐欐硶锛岀畝娲佺洿瑙傘€?

## 渚濊禆

- Java 8+
- Gson (鐢ㄤ簬 JSON 瑙ｆ瀽)
- 鏃犲叾浠栧閮ㄤ緷璧?

## 璁稿彲璇?

鏈」鐩伒寰」鐩暣浣撹鍙瘉銆?