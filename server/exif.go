package main

import (
	"encoding/binary"
	"errors"
	"time"
)

// ---------------------------------------------------------------
// 最小 EXIF 拍摄时间解析（零依赖）
//
// 为什么要读照片里的 EXIF：手机拍照后如果客户端没把 taken_at 传上来
// （老版本 App、相册导入、离线补传丢了字段），服务端只能用「服务器收到请求的时刻」，
// 于是「下午 3 点拍的照片、晚上 11 点才传成功」会被记成 23:00，时长全乱。
// 这里从 JPEG 的 APP1 段里把相机写的拍摄时间读出来当兜底，读不到再退回现在。
//
// 只认 JPEG（APP1/Exif），只取 DateTimeOriginal / DateTime 两个 ASCII 字段。
// ---------------------------------------------------------------

var errNoEXIF = errors.New("no exif")

// exifTakenAt 从 JPEG 字节里取拍摄时间。取不到返回 errNoEXIF。
func exifTakenAt(buf []byte) (time.Time, error) {
	if len(buf) < 4 || buf[0] != 0xFF || buf[1] != 0xD8 {
		return time.Time{}, errNoEXIF // 不是 JPEG
	}
	i := 2
	for i+4 <= len(buf) {
		if buf[i] != 0xFF {
			return time.Time{}, errNoEXIF
		}
		marker := buf[i+1]
		//  standalone markers（没有长度字段）
		if marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD7) || marker == 0x01 {
			i += 2
			continue
		}
		if marker == 0xD9 || marker == 0xDA { // EOI / SOS：后面是压缩数据，别再扫
			return time.Time{}, errNoEXIF
		}
		segLen := int(binary.BigEndian.Uint16(buf[i+2 : i+4]))
		if segLen < 2 || i+2+segLen > len(buf) {
			return time.Time{}, errNoEXIF
		}
		payload := buf[i+4 : i+2+segLen]
		if marker == 0xE1 && len(payload) > 6 && string(payload[:6]) == "Exif\x00\x00" {
			return parseEXIFFromTIFF(payload[6:])
		}
		i += 2 + segLen
	}
	return time.Time{}, errNoEXIF
}

// parseEXIFFromTIFF 解析 TIFF 头 + IFD，找 DateTimeOriginal（优先）或 DateTime。
func parseEXIFFromTIFF(b []byte) (time.Time, error) {
	if len(b) < 8 {
		return time.Time{}, errNoEXIF
	}
	var bo binary.ByteOrder
	switch string(b[:2]) {
	case "II":
		bo = binary.LittleEndian
	case "MM":
		bo = binary.BigEndian
	default:
		return time.Time{}, errNoEXIF
	}
	if bo.Uint16(b[2:4]) != 0x2A {
		return time.Time{}, errNoEXIF
	}
	ifd0 := int(bo.Uint32(b[4:8]))
	if ifd0 <= 0 || ifd0+2 > len(b) {
		return time.Time{}, errNoEXIF
	}

	// 先扫 IFD0（DateTime 在这里），同时记下 ExifIFD 的偏移
	var exifIFD int
	raw, found := scanIFD(b, bo, ifd0, func(tag int, typ uint16, count int, valOff int) (string, bool) {
		switch tag {
		case 0x8769: // Exif IFD pointer
			if typ == 4 && count == 1 && valOff+4 <= len(b) {
				exifIFD = int(bo.Uint32(b[valOff : valOff+4]))
			}
		case 0x0132: // DateTime
			if s, ok := asciiAt(b, valOff, 20); ok {
				return s, true
			}
		}
		return "", false
	})
	if found {
		return parseEXIFTime(raw)
	}

	// DateTimeOriginal 在 ExifIFD 里
	if exifIFD > 0 && exifIFD+2 <= len(b) {
		raw, found = scanIFD(b, bo, exifIFD, func(tag int, typ uint16, count int, valOff int) (string, bool) {
			if tag == 0x9003 || tag == 0x9004 { // DateTimeOriginal / Digitized
				if s, ok := asciiAt(b, valOff, 20); ok {
					return s, true
				}
			}
			return "", false
		})
		if found {
			return parseEXIFTime(raw)
		}
	}
	return time.Time{}, errNoEXIF
}

// scanIFD 遍历一个 IFD 的条目，回调返回非空字符串即采用。
func scanIFD(b []byte, bo binary.ByteOrder, off int, want func(tag int, typ uint16, count int, valOff int) (string, bool)) (string, bool) {
	if off+2 > len(b) {
		return "", false
	}
	n := int(bo.Uint16(b[off : off+2]))
	if n <= 0 || n > 512 {
		return "", false
	}
	p := off + 2
	for i := 0; i < n; i++ {
		if p+12 > len(b) {
			return "", false
		}
		tag := int(bo.Uint16(b[p : p+2]))
		typ := bo.Uint16(b[p+2 : p+4])
		count := int(bo.Uint32(b[p+4 : p+8]))
		// ASCII(2) 类型且长度 > 4 时，值存放在 offset 指向的位置
		valOff := p + 8
		if typ == 2 && count > 4 {
			o := int(bo.Uint32(b[p+8 : p+12]))
			if o <= 0 || o+count > len(b) {
				valOff = -1
			} else {
				valOff = o
			}
		}
		if valOff >= 0 {
			if s, ok := want(tag, typ, count, valOff); ok {
				return s, true
			}
		}
		p += 12
	}
	return "", false
}

// asciiAt 取一段以 NUL 结尾的 ASCII 字符串。
func asciiAt(b []byte, off, max int) (string, bool) {
	if off < 0 || off+max > len(b) {
		if off < 0 || off >= len(b) {
			return "", false
		}
	}
	end := off
	for end < len(b) && end-off < max && b[end] != 0 {
		end++
	}
	if end == off {
		return "", false
	}
	return string(b[off:end]), true
}

// parseEXIFTime EXIF 时间格式固定 "2006:01:02 15:04:05"，也可能只有日期或全空格。
// EXIF 不带时区，解析出来按 UTC 承载，真正用的时候要经 exifInLoc 落到客户端时区。
func parseEXIFTime(raw string) (time.Time, error) {
	if len(raw) < 10 {
		return time.Time{}, errNoEXIF
	}
	layouts := []string{"2006:01:02 15:04:05", "2006:01:02 15:04", "2006:01:02"}
	for _, l := range layouts {
		if t, err := time.Parse(l, raw); err == nil {
			return t, nil
		}
	}
	return time.Time{}, errNoEXIF
}

// exifInLoc 把 EXIF 时间按「相机本地时间」解释到 loc 时区。
// EXIF 只写墙钟时间不写时区，直接当 UTC 会差好几个小时，
// 所以这里取出年月日时分秒，再按客户端的时区重新组装。
func exifInLoc(t time.Time, loc *time.Location) time.Time {
	return time.Date(t.Year(), t.Month(), t.Day(), t.Hour(), t.Minute(), t.Second(), 0, loc)
}
