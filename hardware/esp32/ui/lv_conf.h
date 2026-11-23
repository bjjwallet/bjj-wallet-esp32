// SPDX-License-Identifier: GPL-3.0-or-later
#ifndef LV_CONF_H
#define LV_CONF_H
#define LV_COLOR_DEPTH 16
// Simple embedded renderer: no shadow/mask callback path, less code/RAM.
// Rectangles, text, embedded images, opacity/position animation remain available.
#define LV_DRAW_SW_COMPLEX 0
#define LV_USE_OS LV_OS_NONE
#define LV_USE_STDLIB_MALLOC LV_STDLIB_CUSTOM
#define LV_USE_STDLIB_STRING LV_STDLIB_CLIB
#define LV_USE_STDLIB_SPRINTF LV_STDLIB_CLIB
#define LV_USE_LOG 0
#define LV_USE_ASSERT_NULL 1
#define LV_USE_ASSERT_MALLOC 1
#define LV_USE_LABEL 1
#define LV_USE_BUTTON 1
#define LV_USE_CHECKBOX 1
#define LV_USE_BUTTONMATRIX 1
#define LV_USE_TEXTAREA 1
#define LV_USE_KEYBOARD 1
#define LV_USE_FLEX 1
#define LV_USE_IMAGE 1
#define LV_USE_QRCODE 1
#define LV_USE_ANIMIMG 1
#define LV_USE_GIF 1
#define LV_USE_SNAPSHOT 1
#define LV_FONT_MONTSERRAT_14 1
#define LV_FONT_MONTSERRAT_18 1
#define LV_FONT_DEFAULT &lv_font_montserrat_14
// File-based media is deliberately unavailable; assets belong in firmware flash.
#define LV_USE_FS_STDIO 0
#define LV_USE_FS_POSIX 0
#define LV_USE_FS_FATFS 0
#endif
