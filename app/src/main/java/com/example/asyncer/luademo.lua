-- ============================================================================
-- Asyncer LuaJ Full Feature Demonstration Script
-- Demonstrates: Device/Battery/Network info, SharedPreferences, Filesystem,
--               SQLDroid (direct & HTTP Base64 POST), File Downloads,
--               Sync Job execution, Android Dialogs/Prompts/Menus,
--               Notifications, Clipboard, Vibration/Beep, Shell, and luajava
-- ============================================================================

log("=== Asyncer Lua Demo Started ===")
toast("Starting Asyncer Lua Demo...", false)

-- 1. Inspect Job Context Variables injected by Asyncer
log("Job Context -> Host: " .. tostring(SMB_HOST) ..
    " | Share/Path: " .. tostring(SMB_SHARE) ..
    " | Local Dir: " .. tostring(LOCAL_DIR))

-- 2. Device, Battery & Network Telemetry
local dev = device_info()
local bat = battery_info()
local net = network_info()

local sys_summary = string.format(
    "Device: %s %s (Android %s, SDK %d)\nBattery: %d%% (Charging: %s, %.1f°C)\nNetwork: %s (%s) IP: %s",
    tostring(dev.manufacturer), tostring(dev.model), tostring(dev.release), dev.sdk or 0,
    bat.level or -1, tostring(bat.charging), bat.temp or 0.0,
    tostring(net.type), tostring(net.connected), tostring(net.ip or "N/A")
)
log(sys_summary)

-- 3. Persistent Key-Value Storage (SharedPreferences)
local run_count = (tonumber(prefs_get("demo_run_count", "0")) or 0) + 1
prefs_set("demo_run_count", tostring(run_count))
log("Script run counter (SharedPreferences): " .. run_count)

-- 4. Interactive Android Dialogs (only when MainActivity UI is foregrounded)
local selected_action = "All"
local custom_note = "Hello from LuaJ! Quotes ' \" & = % + \n Newlines & Unicode 你好"

if ACTIVITY ~= nil then
    -- List selection menu
    local idx, choice = choose("Asyncer Lua Demo (Run #" .. run_count .. ")", {
        "Run Full Demo (SQL + Download + Sync)",
        "SQLDroid Database Test Only",
        "Download & Filesystem Test Only",
        "Trigger Sync Job 1"
    })
    if idx ~= nil then
        selected_action = choice
        log("User selected option #" .. idx .. ": " .. choice)
    end

    -- Text input prompt
    local entered = prompt("SQL Note Input", "Enter text with any special characters to store in SQLite:", custom_note)
    if entered ~= nil then
        custom_note = entered
    end
else
    log("Running in background/headless mode (skipping modal UI prompts).")
end

-- 5. Filesystem Operations (Internal storage & SD card)
local base_dir = sdcard_dir() .. "/sync/lua_demo"
mkdirs(base_dir)

local log_file = base_dir .. "/demo_log.txt"
write_file(log_file, "Run #" .. run_count .. " | " .. os.date("%Y-%m-%d %H:%M:%S") .. "\n" .. sys_summary .. "\n\n", true)

local file_content = read_file(log_file)
log("Log file size on disk: " .. (file_content and #file_content or 0) .. " bytes")

-- List directory contents
local entries = list_dir(base_dir)
log("Directory listing for " .. base_dir .. " (" .. #entries .. " items):")
for i = 1, #entries do
    local item = entries[i]
    log(string.format("  %s [%s] (%d bytes)", item.name, item.is_dir and "DIR" or "FILE", item.size))
end

-- 6. Direct SQLDroid Queries (sql_query)
local db_path = base_dir .. "/lua_demo.db"
if selected_action == "All" or selected_action == "SQLDroid Database Test Only" then
    log("Testing direct SQLDroid database at: " .. db_path)

    -- Create table & insert parameterized/escaped values
    local escaped_note = string.gsub(custom_note, "'", "''")
    local setup_sql = [[
        CREATE TABLE IF NOT EXISTS events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            run_num INTEGER,
            note TEXT,
            device_model TEXT,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP
        );
        INSERT INTO events (run_num, note, device_model)
        VALUES (]] .. run_count .. [[, ']] .. escaped_note .. [[', ']] .. tostring(dev.model) .. [[');
    ]]

    local res, err = sql_query(db_path, setup_sql)
    if err ~= nil then
        log("SQLDroid insert error: " .. err)
    else
        log("SQLDroid rows affected: " .. tostring(res.rows_affected))
    end

    -- Query rows back (accessible by both column name and 1-based column index)
    local rows, qerr = sql_query(db_path, "SELECT id, run_num, note, created_at FROM events ORDER BY id DESC LIMIT 5;")
    if qerr ~= nil then
        log("SQLDroid select error: " .. qerr)
    else
        log("Latest " .. #rows .. " rows in SQLite:")
        for i = 1, #rows do
            local r = rows[i]
            log(string.format("  #%s (Run %s) [%s]: %s", tostring(r.id), tostring(r.run_num), tostring(r.created_at), tostring(r.note)))
        end
    end
end

-- 7. Network File Download & HTTP GET / POST
if selected_action == "All" or selected_action == "Download & Filesystem Test Only" then
    local dl_target = base_dir .. "/robots_sample.txt"
    local ok, bytes_or_err = download("https://www.rfc-editor.org/rfc/rfc2606.txt", dl_target)
    if ok then
        log("Download succeeded! Saved " .. tostring(bytes_or_err) .. " bytes to " .. dl_target)
    else
        log("Download failed: " .. tostring(bytes_or_err))
    end

    -- HTTP GET example
    local status, body = http_get("https://httpbin.org/get", { ["X-Asyncer-Run"] = tostring(run_count) })
    log("HTTP GET status=" .. tostring(status) .. " body_len=" .. tostring(body and #body or 0))
end

-- 8. Calling Asyncer's Local SQL & Sync HTTP Server via POST + Base64
-- Helper to Base64-encode UTF-8 strings in pure Lua so any character passes safely
local b64chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
local function to_base64(data)
    return ((data:gsub(".", function(x)
        local r, b = "", x:byte()
        for i = 8, 1, -1 do r = r .. (b % 2 ^ i - b % 2 ^ (i - 1) > 0 and "1" or "0") end
        return r
    end) .. "0000"):gsub("%d%d%d?%d?%d?%d?", function(x)
        if (#x < 6) then return "" end
        local c = 0
        for i = 1, 6 do c = c + (x:sub(i, i) == "1" and 2 ^ (6 - i) or 0) end
        return b64chars:sub(c + 1, c + 1)
    end) .. ({ "", "==", "=" })[#data % 3 + 1])
end

-- Example POST to local SQL HTTP Server (if enabled on port 8080)
local sql_b64 = to_base64("SELECT sqlite_version() AS ver, '" .. string.gsub(custom_note, "'", "''") .. "' AS echo;")
local http_code, http_resp = http_post(
    "http://127.0.0.1:8080/sql",
    "user=admin&pass=&query_b64=" .. sql_b64,
    "application/x-www-form-urlencoded; charset=UTF-8"
)
log("Local HTTP /sql response (" .. tostring(http_code) .. "): " .. tostring(http_resp))

-- 9. Triggering a Configured Sync Job from Lua
if selected_action == "Trigger Sync Job 1" or (ACTIVITY ~= nil and confirm("Run Sync Job 1?", "Do you want to execute Sync Job 1 now?")) then
    log("Triggering Sync Job 1...")
    local sync_res = run_sync(1)
    log("Sync Job 1 result: " .. tostring(sync_res))
end

-- 10. Shell Execution, Clipboard, Notification, Vibration & Sound
local exit_code, stdout, stderr = shell("uname -a")
log("Shell 'uname -a' (exit " .. exit_code .. "): " .. string.gsub(stdout, "\n", ""))

set_clipboard("Asyncer Run #" .. run_count .. " complete")
log("Clipboard readback: " .. get_clipboard())

notify(1001, "Asyncer Lua Complete", "Run #" .. run_count .. " finished on " .. tostring(dev.model))
vibrate(150)
beep(nil, 150) -- default notification beep for 150ms

-- 11. Direct Java Interop via luajava (e.g., querying free storage space)
local StatFs = luajava.bindClass("android.os.StatFs")
local stat = luajava.new(StatFs, sdcard_dir())
local free_mb = (stat:getAvailableBlocks() * stat:getBlockSize()) / (1024 * 1024)
log(string.format("Free space on %s: %.1f MB", sdcard_dir(), free_mb))

-- 12. Final Summary Dialog (or Toast if running in background)
local final_msg = string.format(
    "Demo Run #%d finished!\n\nFree Storage: %.1f MB\nDB: %s\nLog: %s",
    run_count, free_mb, db_path, log_file
)

if ACTIVITY ~= nil then
    dialog("Asyncer Lua Demo Complete", final_msg)
else
    toast("Asyncer Lua Demo Complete (Run #" .. run_count .. ")", true)
end

-- Optional: uncomment to open Android's share sheet or a URL
-- share_text("Asyncer Report", final_msg)
-- open_url("http://127.0.0.1:8080/")

log("=== Asyncer Lua Demo Finished ===")
return "Completed Run #" .. run_count