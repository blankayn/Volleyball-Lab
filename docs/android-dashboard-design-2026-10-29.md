# Coach dashboard update

Quick Actions is replaced by a grouped Coaching Workspace above the review queue.
Players, Assignments and Player questions each keep their existing destination.
The previous standalone questions shortcut is removed to avoid duplication; coach
profile settings remain available through the workspace footer.

The focus banner uses the existing indoor volleyball practice photograph from
`coach-web/public/assets/volleyball-team-pavel-danilyuk.jpg`, copied unchanged as
`app/src/main/res/drawable-nodpi/coach_dashboard_court.jpg`. Photographer: Pavel Danilyuk
on Pexels. Source attribution is recorded in the website's `PHOTO-SOURCES.md`.
No generated artwork or network image request is used. A dark gradient protects
text contrast, and banner height follows content, including large text.

Native fixture checks cover navigation callbacks for all workspace destinations
and the review action at 320 dp / 160% text, with screenshots at normal text size
and large text size. These use fake callbacks and do not modify user records.

Verification completed: all four `CoachWorkspaceDeviceTest` checks passed,
including every workspace destination and review callback at 160% text size.
Normal and large-text screenshots were inspected. Debug/test APK compilation,
optimized preview packaging and release lint passed. Initial emulator startup
attempts timed out; after normal app startup warmed the emulator, the isolated
check and full four-test run passed. No application change was needed for this.
