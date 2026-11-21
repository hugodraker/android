-- Binary, Hashing & Base64
write_binary("/sdcard/sync/bin.dat", { 0x89, 0x50, 0x4E, 0x47, 0x00, 0xFF }, false)
local raw = read_binary("/sdcard/sync/bin.dat")
log("SHA-256: " .. hash_file("SHA-256", "/sdcard/sync/bin.dat"))
log("Base64:  " .. base64_encode(raw))

-- File Copy, Rename, Move & Metadata
copy_file("/sdcard/sync/bin.dat", "/sdcard/sync/bin_copy.dat")
rename_file("/sdcard/sync/bin_copy.dat", "bin_renamed.dat")
move_file("/sdcard/sync/bin_renamed.dat", "/sdcard/sync/archive/bin_moved.dat")

-- Sensors & GPS
local accel = read_sensor("accelerometer", 1500)
if accel then log(string.format("Accel: x=%.2f y=%.2f z=%.2f", accel.x, accel.y, accel.z)) end
local loc = get_location()
if loc.lat then log("GPS: " .. loc.lat .. ", " .. loc.lon) end

-- Speech & Sound
speak("Sync and sensor check complete.", 1.0, 1.0)
-- play_sound("/sdcard/sync/alert.mp3", false)

-- HTTPS (with trustAllCerts=true for self-signed local servers), FTP & SMB
local code, body = http_get("https://192.168.1.50:8443/status", nil, true)
ftp_upload("192.168.1.50", 21, "user", "pass", "/sdcard/sync/bin.dat", "/incoming/bin.dat")
smb_upload("192.168.1.50", 445, "user", "pass", "PUBLIC", "/sdcard/sync/bin.dat", "incoming\\bin.dat")

-- Extra GUI Pickers, Progress & HTML Popup
if ACTIVITY ~= nil then
    local pd = progress_dialog("Processing", "Step 1 of 2...")
    sleep(800)
    pd.update("Step 2 of 2...")
    sleep(800)
    pd.close()

    local date_str = date_picker()
    local time_str = time_picker()
    html_dialog("Summary", "<h3>Scheduled: " .. tostring(date_str) .. " " .. tostring(time_str) .. "</h3>")
end