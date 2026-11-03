import { esc, initials } from "./format";
import { icon } from "./icons";

export function profileHeader({ name, subtitle, uid, photo = "", banner = "", editable = false }: {
  name: string; subtitle: string; uid: string; photo?: string; banner?: string; editable?: boolean;
}): string {
  return `<section class="social-profile" aria-label="${esc(name)} profile">
    <div class="social-profile-cover" ${editable ? 'id="profile-banner-preview"' : `data-profile-banner="${esc(uid)}"`} ${banner ? `style="background-image:url('${esc(banner)}')"` : ""}>
      ${editable ? `<button class="social-cover-edit" type="button" data-edit-cover>${icon("camera")}<span>Edit cover photo</span></button>` : ""}
    </div>
    <div class="social-profile-details">
      <div class="social-profile-portrait">
        <div class="social-profile-avatar" ${editable ? 'id="profile-photo-preview"' : `data-profile-avatar="${esc(uid)}"`}>${photo ? `<img src="${esc(photo)}" alt="Profile photo" />` : esc(initials(name))}</div>
        ${editable ? `<button class="social-photo-edit" type="button" data-edit-profile-photo aria-label="Change profile picture">${icon("camera")}</button>` : ""}
      </div>
      <div class="social-profile-name"><h2>${esc(name)}</h2><p>${esc(subtitle)}</p></div>
    </div>
  </section>`;
}
