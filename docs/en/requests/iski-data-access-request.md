# Draft: data access request to İSKİ

Türkçe (the version to send): [../../tr/talepler/iski-veri-erisim-talebi.md](../../tr/talepler/iski-veri-erisim-talebi.md)

Status: draft, not sent. The Turkish text is the one that gets sent; this is a translation for the repo.

Where to send: İSKİ is a public body, so the request can go through the Right to Information Act No. 4982 (İSKİ's information request form or CİMER). The legal response time is 15 working days. Check the channel on İSKİ's site before sending. The same text can also go to the İBB Open Data Portal team.

---

**Subject:** Request for permission to access fault and outage data automatically

Dear İSKİ General Directorate,

I am [Name Surname], and as part of a DevOps internship at [school / company] I am building an open source, non-commercial project called "Kesinti Haritası" (outage map, https://github.com/agern28/kesinti-haritasi). It shows electricity and water outages published by official sources on a single map. Each record shows the name of the source institution and a link to the original announcement.

We would like the information published on the "Arıza ve Kesintiler" (faults and outages) page of İSKİ's website to appear on this map as well. The service behind that page (iskiapi.iski.istanbul) requires an access key. We don't want to use the key embedded in the site's code without permission, so we are asking you for permission and access.

We are asking for one of the following:

1. Access to the regional fault/outage list (`iski/bolgeselAriza/listesi`) with an access key issued for our project,
2. or publishing the same data on the İBB Open Data Portal as a dataset that is updated regularly (daily or more often).

How we will use the data:

- At most one request every 5 minutes, with a User-Agent that contains the project name and a contact address.
- The data is shown unchanged, with İSKİ and a link to the original page as the source.
- No personal data is processed. Only district, neighbourhood, start, estimated end and description fields are used.
- You can stop the access at any time. If you ask, we stop using the data immediately.

We also plan to use the "İstanbul'da Meydana Gelen Su Kesintileri" (water outages in Istanbul) dataset on the İBB Open Data Portal for historical outage statistics per neighbourhood. If that dataset could be updated for the period after 2024, it would be very valuable for us.

Kind regards,

[Name Surname]
[E-mail]
[Phone, optional]
[Date]
