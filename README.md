# ServuxFolia

[![Build](https://github.com/njes9701/ServuxFolia/actions/workflows/build.yml/badge.svg)](https://github.com/njes9701/ServuxFolia/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/njes9701/ServuxFolia)](https://github.com/njes9701/ServuxFolia/releases/latest)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)

ServuxFolia 是針對 **Folia** 設計的伺服器插件，實作 Litematica、Tweakeroo
與 MiniHUD 使用的公開 Servux 通訊協議。它不需要 Fabric 伺服器，也不會載入或
內嵌 Servux。

> ServuxFolia 是獨立的 clean-room 相容實作，並非 Servux 官方移植版。

## 相容環境

| 元件 | 需求 |
| --- | --- |
| 伺服器 | Folia 26.2 |
| Java | 25 |
| 藍圖客戶端 | 對應 26.2 的 Litematica／Tweakeroo |
| 資訊顯示客戶端 | 對應 26.2 的 MiniHUD |

此插件使用 Folia/Paper 的版本專屬內部介面，因此**不能假設能在其他 Minecraft、
Paper 或 Folia 版本運作**。升級伺服器前請先等待對應版本的插件。

## 功能

- Easy Place V3 精確放置協議，支援朝向與方塊狀態
- 放置後權威方塊／物品欄重新同步，降低客戶端預測造成的幽靈方塊
- `servux:litematics` v1 metadata、單筆與 Bulk NBT 查詢
- Litematica Direct Paste：方塊、方塊實體、實體、pending ticks、全域旋轉／鏡像與 replace modes
- MiniHUD `servux:structures` v2 結構主框與組件框
- MiniHUD `servux:entity_data` v1 蜂巢與村民 NBT，可顯示蜂巢蜜蜂數量及村民交易
- Folia `RegionScheduler`／`EntityScheduler` 安全存取
- 封包大小、距離、頻率、並行貼上及 splitter session 限制

Easy Place、MiniHUD 結構框、Direct Paste 與蜂巢資訊皆已用真實客戶端完成基本實機測試。

## 安裝

1. 從 [Releases](https://github.com/njes9701/ServuxFolia/releases/latest) 下載最新的 `ServuxFolia` JAR。
2. 將 JAR 放進 Folia 伺服器的 `plugins` 資料夾。
3. 使用 Java 25 啟動伺服器。
4. 需要修改功能時，編輯 `plugins/ServuxFolia/config.yml` 後執行 `/sfolia reload`。

不需要在伺服器安裝 Fabric、Servux、Litematica、Tweakeroo 或 MiniHUD；這些是客戶端模組。

## 客戶端設定

### Easy Place

Easy Place V3 預設啟用，不依賴完整 Servux metadata 廣告。於 Litematica 開啟
Easy Place 模式即可；伺服器權限為 `litematica.place`，預設所有玩家可用。

可用 `/sfolia` 查看計數：

```text
captured/applied/rejected=... | pending=... | resynced=...
```

### MiniHUD 結構框

1. 玩家需有 `servuxfolia.structures` 權限（預設所有玩家）。
2. 在 MiniHUD 開啟所需的 Structure Overlay。
3. `/sfolia` 應顯示 `structures=true (clients=1)`。

插件只掃描已載入區塊，不會為了顯示結構而強制生成或載入遠方區塊。
`minecraft:buried_treasure` 因生存伺服器資訊安全考量，預設列入黑名單。

### MiniHUD 蜂巢資訊

1. 玩家需有 `servuxfolia.entitydata` 權限（預設所有玩家）。
2. 在 MiniHUD 開啟 `Generic -> entityDataSync`。
3. 開啟 `Info Lines -> infoBeeCount`，準星對準蜂巢或蜂窩。
4. `/sfolia` 應顯示 `entityData=true (clients=1, tracked=...)`。

預設 allowlist 只公開 `minecraft:beehive`，避免任意方塊實體 NBT 被讀取。

### MiniHUD 村民交易

1. 玩家需有 `servuxfolia.entitydata` 權限（預設所有玩家）。
2. 在 MiniHUD 開啟 `Generic -> entityDataSync`。
3. 開啟 `Renderers -> overlayVillagerInfo`。
4. 依需求調整 `villagerOfferEnchantmentBooks`、價格範圍及最高等級等選項。

插件只回覆該玩家目前正在追蹤、距離限制內的實體。預設實體 allowlist 為
`minecraft:villager` 與 `minecraft:zombie_villager`，不會公開玩家或其他實體 NBT。

### Direct Paste 與 Bulk NBT

完整 Servux 能力廣告及 Direct Paste 預設關閉。管理員確認權限與資源限制後，在
`plugins/ServuxFolia/config.yml` 啟用：

```yaml
litematics:
  advertise: true

direct-paste:
  enabled: true
```

Direct Paste 還要求 `servuxfolia.paste`（預設 OP），而且預設只允許創造模式玩家。
大型藍圖請依伺服器硬體調整 `max-volume`、`max-uncompressed-bytes` 與
`max-concurrent`，不要直接移除限制。

## 指令與權限

| 指令／權限 | 預設 | 用途 |
| --- | --- | --- |
| `/servuxfolia`、`/sfolia` | 所有人 | 顯示功能、客戶端與放置統計 |
| `/sfolia reload` | OP | 重新載入設定 |
| `servuxfolia.admin` | OP | 管理指令 |
| `litematica.place` | 所有人 | Easy Place 精確放置 |
| `servuxfolia.litematics` | 所有人 | Litematica Servux data channel |
| `servuxfolia.paste` | OP | Direct Paste |
| `servuxfolia.structures` | 所有人 | MiniHUD 結構資料 |
| `servuxfolia.entitydata` | 所有人 | MiniHUD 方塊實體資料 |

專案根目錄另附 [permissions.yml](permissions.yml)，可作為 Folia 伺服器根目錄
`permissions.yml` 的群組權限範本。插件本身已帶有上述預設值，不複製範本也能正常使用。

## 安全設計

- 所有世界存取都派送至正確的 Folia region/entity scheduler
- NBT 請求有玩家距離與每秒頻率限制
- inbound、reassembled 與 response payload 都有硬上限
- Direct Paste 有全域及每位玩家的並行限制
- 結構掃描只讀取已載入區塊，並提供白名單／黑名單
- MiniHUD entity data 預設只允許蜂巢

建議保留預設限制，再依可信玩家數與伺服器效能逐步放寬。

## 已知限制

- 僅支援 Folia 26.2／Java 25。
- Direct Paste 的每個 subregion 個別旋轉或鏡像目前會明確拒絕；subregion 啟用狀態與位置覆寫可用。
- 尚未廣告大型多幀 `Litematic-Transmit*` 檔案傳輸；一般 inline Litematica paste 可用。
- 結構顯示刻意不強制載入區塊，因此未載入區域不會立即出現。

## 從原始碼建置

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.2'
.\gradlew.bat --no-daemon clean test build
```

輸出位於 `build/libs/ServuxFolia-<version>.jar`。`main` 分支可能包含尚未發布的
`-SNAPSHOT` 功能；正式伺服器建議優先使用 [Releases](https://github.com/njes9701/ServuxFolia/releases)。

## 專案來源與授權

ServuxFolia 以 [GPL-3.0](LICENSE) 發布。協議研究與相容性參考：

- [Servux](https://github.com/sakura-ryoko/servux)
- [Litematica](https://github.com/sakura-ryoko/litematica)
- [MiniHUD](https://github.com/sakura-ryoko/minihud)
- [Tweakeroo](https://github.com/sakura-ryoko/tweakeroo)
- [Folia](https://github.com/PaperMC/Folia)
- [LitematicaFolia](https://forgejo.ekaii.fr/admin_ekaii/litematica-folia-ekaii)

第三方專案的授權與使用範圍請見 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## English

ServuxFolia is a clean-room, Folia-native server plugin implementing the public
Servux wire protocols used by Litematica, Tweakeroo and MiniHUD. It targets
Folia 26.2 and Java 25, providing Easy Place V3, bounded Litematica NBT/Direct
Paste support, MiniHUD structure overlays, beehive data and Folia-safe tracked
villager trade sync. The `main` branch may contain unreleased snapshot features;
use GitHub Releases for stable server deployments. See the sections above for
installation, permissions, safety defaults and known limitations.
