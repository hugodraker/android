-- ============================================================================
-- Asyncer Complete LuaJ Bridge Test Suite
-- Exercises every exposed function in LuaAndroidBridge.java
-- ============================================================================

log("=== 1. LOGGING, TIMING & GLOBALS ===")
log("CONTEXT=" .. tostring(CONTEXT))
log("ACTIVITY=" .. tostring(ACTIVITY))
log("SMB_HOST=" .. tostring(SMB_HOST) .. " | SMB_SHARE=" .. tostring(SMB_SHARE) .. " | LOCAL_DIR=" .. tostring(LOCAL_DIR))
sleep(100)

log("=== 2. TELEMETRY, SCREEN & PREFERENCES ===")
local dev = device_info()
local bat = battery_info()
local net = network_info()
local scr = screen_info()

log(string.format("Device: %s %s (Android %s, SDK %s)",
    tostring(dev.manufacturer), tostring(dev.model), tostring(dev.release), tostring(dev.sdk)))
log(string.format("Battery: %s%% | Charging=%s | Temp=%sC",
    tostring(bat.level), tostring(bat.charging), tostring(bat.temp)))
log(string.format("Network: connected=%s | type=%s | ip=%s | ssid=%s",
    tostring(net.connected), tostring(net.type), tostring(net.ip), tostring(net.ssid)))
log(string.format("Screen: %dx%d px | density=%.2f | dpi=%d",
    scr.width or 0, scr.height or 0, scr.density or 0, scr.dpi or 0))

local run_num = (tonumber(prefs_get("full_suite_runs", "0")) or 0) + 1
prefs_set("full_suite_runs", tostring(run_num))
log("Saved SharedPreferences counter: full_suite_runs=" .. prefs_get("full_suite_runs", "0"))

log("=== 3. DIRECTORIES & TEXT/BINARY FILESYSTEM ===")
log("files_dir()  = " .. files_dir())
log("cache_dir()  = " .. cache_dir())
log("sdcard_dir() = " .. sdcard_dir())

local work_dir = sdcard_dir() .. "/sync/lua_full_test"
local sub_dir  = work_dir .. "/nested"
mkdirs(sub_dir)

-- Text write/read
local txt_path = work_dir .. "/notes.txt"
write_file(txt_path, "Line 1: Initial text\n", false)
write_file(txt_path, "Line 2: Appended UTF-8 text (Accents: éàü, CJK: 测试)\n", true)
log("file_exists(notes.txt) = " .. tostring(file_exists(txt_path)))
log("read_file(notes.txt):\n" .. tostring(read_file(txt_path)))

-- Copy, rename, move, metadata & directory listing
local copy_path    = work_dir .. "/notes_copy.txt"
local renamed_path = work_dir .. "/notes_renamed.txt"
local moved_path   = sub_dir .. "/notes_moved.txt"

log("copy_file   -> " .. tostring(copy_file(txt_path, copy_path)))
log("rename_file -> " .. tostring(rename_file(copy_path, "notes_renamed.txt")))
log("move_file   -> " .. tostring(move_file(renamed_path, moved_path)))

local finfo = file_info(moved_path)
log(string.format("file_info(notes_moved.txt): size=%d, is_file=%s, can_read=%s",
    finfo.size or 0, tostring(finfo.is_file), tostring(finfo.can_read)))

local dir_items = list_dir(work_dir)
log("list_dir(" .. work_dir .. ") count = " .. #dir_items)
for i = 1, #dir_items do
    log("  [" .. (dir_items[i].is_dir and "DIR" or "FILE") .. "] " .. dir_items[i].name)
end

log("=== 4. BINARY, BYTE ARRAYS, BASE64, HEX & HASHING ===")
local bin_path   = work_dir .. "/sample.bin"
local bytes_path = work_dir .. "/sample_bytes.bin"

-- write_binary with raw Lua string containing NUL and high bytes
local raw_payload = "HDR\0\1\2\254\255END"
write_binary(bin_path, raw_payload, false)
write_binary(bin_path, "\xAA\xBB", true)
local read_back_bin = read_binary(bin_path)
log("read_binary length = " .. #read_back_bin)

-- write_bytes / read_bytes with 1-based byte integer table (0..255)
write_bytes(bytes_path, { 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A }, false)
write_bytes(bytes_path, { 0x00, 0xFF }, true)
local byte_tbl = read_bytes(bytes_path)
log("read_bytes count = " .. #byte_tbl .. " | first=" .. tostring(byte_tbl[1]) .. " last=" .. tostring(byte_tbl[#byte_tbl]))

-- Base64 & Hex encode/decode
local b64_str = base64_encode(read_back_bin)
local b64_dec = base64_decode(b64_str)
log("base64_encode -> " .. b64_str .. " | roundtrip_ok=" .. tostring(b64_dec == read_back_bin))

local hex_str = hex_encode(read_back_bin)
local hex_dec = hex_decode(hex_str)
log("hex_encode    -> " .. hex_str .. " | roundtrip_ok=" .. tostring(hex_dec == read_back_bin))

-- MD5, SHA-1, SHA-256 hashing on memory & files
log("hash_data(MD5)    = " .. tostring(hash_data("MD5", raw_payload)))
log("hash_data(SHA-1)  = " .. tostring(hash_data("SHA-1", raw_payload)))
log("hash_file(SHA-256)= " .. tostring(hash_file("SHA-256", bin_path)))

log("=== 5. JSON ENCODE & DECODE ===")
local encoded_json = json_encode({
    app = "Asyncer",
    run = run_num,
    active = true,
    tags = { "lua", "sqldroid", "smb", "ftp" }
})
log("json_encode -> " .. tostring(encoded_json))
local decoded_tbl = json_decode(encoded_json)
log("json_decode -> app=" .. tostring(decoded_tbl.app) .. ", first_tag=" .. tostring(decoded_tbl.tags[1]))

log("=== 6. SQLDROID DIRECT DATABASE QUERIES ===")
local db_path = work_dir .. "/test_suite.db"
local _, sql_err1 = sql_query(db_path, [[
    CREATE TABLE IF NOT EXISTS audit_log (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        run_id INTEGER,
        payload TEXT,
        sha256 TEXT
    );
    INSERT INTO audit_log (run_id, payload, sha256)
    VALUES (]] .. run_num .. [[, 'Special chars: '' " & = % + \n Unicode: 你好', ']] .. tostring(hash_file("SHA-256", bin_path)) .. [[');
]])
if sql_err1 then log("SQL insert error: " .. sql_err1) end

local rows, sql_err2 = sql_query(db_path, "SELECT id, run_id, payload, sha256 FROM audit_log ORDER BY id DESC LIMIT 3;")
if sql_err2 then
    log("SQL select error: " .. sql_err2)
else
    for i = 1, #rows do
        log(string.format("  DB Row #%s: run=%s | payload=%s", tostring(rows[i].id), tostring(rows[i].run_id), tostring(rows[i].payload)))
    end
end

log("=== 7. HARDWARE SENSORS & GPS LOCATION ===")
local sensors = list_sensors()
log("Total hardware sensors on device: " .. #sensors)
for i = 1, math.min(#sensors, 5) do
    log("  Sensor[" .. sensors[i].type .. "]: " .. sensors[i].name)
end

local accel = read_sensor("accelerometer", 1500)
if accel then
    log(string.format("Accelerometer: x=%.3f, y=%.3f, z=%.3f (acc=%d)", accel.x or 0, accel.y or 0, accel.z or 0, accel.accuracy or 0))
else
    log("Accelerometer: no sample within timeout")
end

local light = read_sensor("light", 1000)
if light then
    log("Ambient Light (lux): " .. tostring(light.x))
end

local loc = get_location()
if loc and loc.lat then
    log(string.format("Last Known Location (%s): lat=%.6f, lon=%.6f, alt=%.1f, acc=%.1fm",
        tostring(loc.provider), loc.lat, loc.lon, loc.alt or 0, loc.accuracy or 0))
else
    log("Last Known Location: unavailable (enable GPS/Network location)")
end

log("=== 8. HTTP, HTTPS, URL ENCODING & DOWNLOAD ===")
local enc = url_encode("SELECT * FROM audit_log WHERE payload LIKE '%你好%' & x=1+2;")
local dec = url_decode(enc)
log("url_encode -> " .. tostring(enc))
log("url_decode -> " .. tostring(dec))

-- Download file over HTTPS (3rd arg = trustAllCerts)
local dl_file = work_dir .. "/downloaded_rfc.txt"
local dl_ok, dl_info = download("https://www.rfc-editor.org/rfc/rfc2606.txt", dl_file, false)
log("download() -> ok=" .. tostring(dl_ok) .. ", bytes/err=" .. tostring(dl_info))

-- HTTP GET
local get_code, get_body = http_get("https://httpbin.org/get", { ["X-Test-Run"] = tostring(run_num) }, false)
log("http_get() -> status=" .. tostring(get_code) .. ", len=" .. tostring(get_body and #get_body or 0))

-- HTTP POST to Asyncer's local SQL HTTP server (if enabled on port 8080)
local sql_b64 = base64_encode("SELECT sqlite_version() AS sqlite_ver, " .. run_num .. " AS run_num;")
local post_code, post_body = http_post(
    "http://127.0.0.1:8080/sql",
    "user=admin&pass=&query_b64=" .. url_encode(sql_b64),
    "application/x-www-form-urlencoded; charset=UTF-8",
    nil,
    false
)
log("http_post(/sql) -> status=" .. tostring(post_code) .. ", resp=" .. tostring(post_body))

-- Generic HTTP PUT/DELETE/HEAD request
local req_code, _ = http_request("HEAD", "https://www.rfc-editor.org/rfc/rfc2606.txt", nil, nil, nil, false)
log("http_request(HEAD) -> status=" .. tostring(req_code))

log("=== 9. FTP & SMB CLIENT OPERATIONS ===")
-- Uses SMB_HOST / SMB_SHARE from active job if set, otherwise skips gracefully
local target_host = (SMB_HOST and #SMB_HOST > 0 and SMB_HOST ~= "192.168.1.100") and SMB_HOST or nil
if target_host then
    -- FTP test (port 21, anonymous)
    log("ftp_mkdir    -> " .. tostring(ftp_mkdir(target_host, 21, "anonymous", "", "/asyncer_lua_test")))
    local f_up_ok, f_up_rep = ftp_upload(target_host, 21, "anonymous", "", txt_path, "/asyncer_lua_test/notes.txt")
    log("ftp_upload   -> " .. tostring(f_up_ok) .. " (" .. tostring(f_up_rep) .. ")")
    local f_list, f_err = ftp_list(target_host, 21, "anonymous", "", "/asyncer_lua_test", false)
    log("ftp_list     -> count=" .. (f_list and #f_list or 0) .. " err=" .. tostring(f_err))
    local f_dn_ok, f_dn_rep = ftp_download(target_host, 21, "anonymous", "", "/asyncer_lua_test/notes.txt", work_dir .. "/from_ftp.txt")
    log("ftp_download -> " .. tostring(f_dn_ok) .. " (" .. tostring(f_dn_rep) .. ")")
    log("ftp_delete   -> " .. tostring(ftp_delete(target_host, 21, "anonymous", "", "/asyncer_lua_test/notes.txt", false)))

    -- SMB test (port 445, guest)
    local shares, s_err = smb_shares(target_host, 445, "guest", "")
    log("smb_shares   -> count=" .. (shares and #shares or 0) .. " err=" .. tostring(s_err))
    local share_name = (SMB_SHARE and #SMB_SHARE > 0) and SMB_SHARE or (shares and shares[1]) or "PUBLIC"
    log("smb_mkdir    -> " .. tostring(smb_mkdir(target_host, 445, "guest", "", share_name, "asyncer_lua_test")))
    log("smb_upload   -> " .. tostring(smb_upload(target_host, 445, "guest", "", share_name, txt_path, "asyncer_lua_test\\notes.txt")))
    local s_list, sl_err = smb_list(target_host, 445, "guest", "", share_name, "asyncer_lua_test")
    log("smb_list     -> count=" .. (s_list and #s_list or 0) .. " err=" .. tostring(sl_err))
    log("smb_download -> " .. tostring(smb_download(target_host, 445, "guest", "", share_name, "asyncer_lua_test\\notes.txt", work_dir .. "/from_smb.txt")))
    log("smb_delete   -> " .. tostring(smb_delete(target_host, 445, "guest", "", share_name, "asyncer_lua_test\\notes.txt")))
else
    log("Skipping live FTP/SMB calls (set a real Host in the active Sync Profile to test).")
end

log("=== 10. SHELL, CLIPBOARD, SPEECH, AUDIO & NOTIFICATIONS ===")
local sh_code, sh_out, sh_err = shell("echo hello_shell && pwd")
log("shell() -> code=" .. sh_code .. " out=" .. string.gsub(sh_out, "\n", " "))

set_clipboard("Asyncer test suite #" .. run_num)
log("clipboard -> " .. get_clipboard())

notify(2026, "Asyncer Test Suite", "Run #" .. run_num .. " reached audio/speech stage")
vibrate(120)
beep(nil, 120)

-- Text-to-Speech (speak) & MediaPlayer (play_sound / stop_sound)
local spoke = speak("Asyncer Lua test suite run " .. run_num .. " complete.", 1.05, 1.0)
log("speak() -> " .. tostring(spoke))
sleep(1500)
stop_speak()

-- Example local audio playback if a ringtone/mp3 exists:
-- play_sound("/sdcard/sync/alert.mp3", false)
-- sleep(1000)
-- stop_sound()

log("=== 11. GUI DIALOGS, PROGRESS, PICKERS & HTML POPUP ===")
if ACTIVITY ~= nil then
    keep_screen_on(true)

    -- Progress dialog controller
    local pd = progress_dialog("Asyncer Suite", "Finalizing checks...")
    sleep(600)
    pd.update("Building interactive report...")
    sleep(600)
    pd.close()

    -- Optional interactive prompts (uncomment any to test interactively)
    -- local ok_go = confirm("Interactive Pickers", "Test multi_choose, date_picker, time_picker, and prompt?")
    -- if ok_go then
    --     local idxs, labels = multi_choose("Select Features", { "SQLDroid", "HTTP/HTTPS", "FTP/SMB", "Sensors" }, { true, true, false, true })
    --     local typed = prompt("Custom Note", "Enter a note:", "Verified!")
    --     local d_str = date_picker()
    --     local t_str = time_picker()
    --     toast("Picked: " .. tostring(d_str) .. " " .. tostring(t_str) .. " (" .. tostring(typed) .. ")", true)
    -- end

    local html = [[
        <html><body style='font-family:sans-serif;padding:12px;'>
        <h3>Asyncer Lua Suite #]] .. run_num .. [[</h3>
        <p><b>Device:</b> ]] .. tostring(dev.manufacturer) .. " " .. tostring(dev.model) .. [[</p>
        <p><b>Battery:</b> ]] .. tostring(bat.level) .. [[% | <b>IP:</b> ]] .. tostring(net.ip or "N/A") .. [[</p>
        <p><b>SHA-256:</b> <code>]] .. tostring(hash_file("SHA-256", bin_path)) .. [[</code></p>
        </body></html>
    ]]
    html_dialog("Suite Summary (WebView)", html)
    keep_screen_on(false)
else
    toast("Asyncer Suite #" .. run_num .. " finished in background", true)
end

-- Cleanup temporary copy/move test files if desired
delete_file(moved_path)

-- Other available system actions (uncomment to trigger):
-- run_sync(1)
-- open_file(txt_path, "text/plain")
-- open_url("http://127.0.0.1:8080/")
-- launch_app("com.android.settings")
-- share_text("Asyncer Log", read_file(txt_path))
-- send_broadcast("com.example.asyncer.CUSTOM_EVENT", { run = run_num, status = "ok" })
-- start_activity("com.android.settings", "com.android.settings.Settings", {})

log("=== ALL TESTS COMPLETED ===")
return "Suite #" .. run_num .. " OK"