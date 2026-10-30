# Player questions layout

The coach inbox follows the supplied sketch: a simple fixed header with a back
arrow and centered title, followed by vertically stacked rounded question cards.
The large status panel is removed; automatic presence reporting is unchanged.
Needs reply and Answered still filter the inbox, and existing reply validation,
drafts, send actions and inactive-roster restrictions are preserved.

Cards have 24 dp padding, a 180 dp minimum height, and grow to accommodate content.
The list has 24 dp gaps and a 640 dp maximum width. The header stays visible while
scrolling, honors the shell's safe-area padding, and has a 48 dp back target.

Native layout fixtures cover centered and fixed header placement, card order,
back/action callbacks, and access to the second card at 160% text size. They do
not send real coaching messages or modify user records.

Verification completed: both native layout checks passed. Normal and 160% text screenshots were reviewed. Debug/test APK compilation, optimized preview packaging and release lint passed.
