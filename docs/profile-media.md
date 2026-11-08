# Profile pictures and banners

Student and coach images use `profileMedia/{uid}`, containing only optional
`photo` and `banner` fields plus `updatedAt`. Images are resized JPEG data URLs,
limited to 120,000 characters for portraits and 300,000 for banners. Shared media
never contains names, addresses, certifications or other account preferences.
Writes merge only the chosen image so changing a banner preserves the portrait.
An empty string means explicitly removed; a missing field may use a legacy source.

Owners can edit their images. Coach images follow their existing signed-in coach
directory visibility. Student images are readable only by the student and a coach
with their Pending or Active roster relationship; ending that relationship removes
access. Deletion fences block reads and writes. Listing the collection is denied.

Android account headers now use a cover image, an overlapping circular portrait,
a cover-edit button at the top right and a camera button overlapping the portrait.
Both buttons open the existing photo picker directly for the signed-in owner.
The coach website uses the same arrangement; student profile headers viewed by a
coach are read-only. Coach public profile banners retain their readability overlay.
Coach directory/connection cards, roster, player
details, review queue and coaching inbox show the shared portrait. Missing or
unreadable portraits keep initials. The coach website watches media for the
displayed players, clears images when permission is revoked or the account changes,
and shows profile banners on profile views only; the dashboard hero keeps its existing
volleyball artwork. Connected students and coaches can see each other's profile banners.
Empty player banners remain hidden. Profile editors offer choose/remove controls; Android also offers Sync photo
for a portrait saved only on that phone by an older app. Version 0.1.18 additionally
backfills a phone-only portrait when its owner opens/resumes the app: a server
transaction writes it only if the canonical photo field is absent. Existing photos
and explicit removals are preserved, and a selected banner is not replaced.
Both profile editors let owners reposition and zoom a selected cover before saving;
the resulting shared banner image preserves that crop for connected viewers.

For coaches, `coachPhotos/{uid}` remains a portrait-only compatibility projection
for older Android versions. A coach's own account header may also read their private
web preferences, but other accounts cannot read those preferences. Explicit web photo
selection writes the compatibility and canonical projections together with the
private settings; routine preference edits do not publish previously private images.
Android photo selection writes the canonical document for either role and the
compatibility projection for coaches. Banner removal preserves the portrait and
does not restore older private images.

On November 3, 2026, the screenshot's coach had a valid web portrait but no
`coachPhotos` projection. The focused live repro failed with `webPhotoPresent=true`
and `sharedPhotoPresent=false`. The existing selected portrait was copied to both
photo projections for that exact coach, without copying other private settings;
the same check then passed with identical portrait content. There was no bulk
publication of other accounts' private photos.

The later V Cirni roster report was reproduced with an Active relationship but no
`profileMedia` document or shared photo. There was no image on the server to render;
the updated student app must open once on the phone holding the older local photo
to perform the backfill. If no local picture remains, the owner chooses one using
the overlapping camera button. This update does not invent or copy another user's
photo into that account.

Verification: 363 Android unit tests, 29 website tests, and five dedicated
Firestore rules/account-cleanup emulator tests passed. Android lint reported zero
errors for version 0.1.18, whose optimized preview APK was built and signature-verified. The new social profile header was visually
checked at desktop and 390-pixel phone widths using the actual web component and
sample profiles. Eight hosted checks verified real student/
coach writes, visibility, removal preserving the portrait, former-coach rejection,
and rejection of private fields. Account cleanup source now removes the owner's
media document; the separate Firebase account-deletion deployment is not activated
by this change. Physical-device image picking and cross-device visual QA remain
to be checked after installing the new APK.
