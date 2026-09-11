# Draft: data access and permission request to İGDAŞ

Türkçe (the version to send): [../../tr/talepler/igdas-veri-erisim-talebi.md](../../tr/talepler/igdas-veri-erisim-talebi.md)

Status: draft, not sent. The Turkish text is the one that gets sent; this is a translation for the repo.

Where to send: İGDAŞ's corporate contact channel (the contact form on the website or the corporate e-mail). Since İGDAŞ is an İBB company, sending a copy to the İBB Open Data Portal team may help. Check the channel on İGDAŞ's site before sending.

Why it's needed: the robots.txt on both İGDAŞ domains (`igdas.istanbul`, `igdas.com.tr`) forbids all automated access (`Disallow: /`). So we don't read outage information without permission.

---

**Subject:** Request for permission to access natural gas outage information automatically

Dear İGDAŞ,

I am [Name Surname], and as part of a DevOps internship at [school / company] I am building an open source, non-commercial project called "Kesinti Haritası" (outage map, https://github.com/agern28/kesinti-haritasi). It shows electricity, water and natural gas outages published by official sources on a single map. Each record shows the name of the source institution and a link to the original announcement.

We would like to show planned and unplanned natural gas outages in Istanbul on the map as well. Your website's robots.txt does not allow automated access, so we don't take data from your site, and we are asking you for permission.

We are asking for one of the following:

1. Permission for automated access to the page or service where outage information is published, under the rules below,
2. or sharing planned and unplanned outages (district, neighbourhood, start and estimated end time) through an API or as a regularly updated dataset on the İBB Open Data Portal.

How we will use the data:

- Planned outage information at most every 15 minutes, fault information at most every 5 minutes, with a User-Agent that contains the project name and a contact address.
- The data is shown unchanged, with İGDAŞ and a link to the original announcement as the source.
- No personal or subscriber data is processed.
- You can stop the access at any time. If you ask, we stop using the data immediately.

Kind regards,

[Name Surname]
[E-mail]
[Phone, optional]
[Date]
