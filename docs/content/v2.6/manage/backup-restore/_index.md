---
title: Back up and restore data
headerTitle: Back up and restore
linkTitle: Back up and restore
description: Back up and restore YugabyteDB.
image: /images/section_icons/manage/enterprise.png
headcontent: Create backups and restore your data.
menu:
  v2.6:
    identifier: backup-restore
    parent: manage
    weight: 702
type: indexpage
---

YugabyteDB is a distributed database that internally replicates data. It is possible to place the regions in separate fault domains, therefore backups for the purpose of data redundancy are not necessary. However, it is an operational best practice to have a backup strategy. For example, an error in the application layer could cause it to write bad data into the database. In such a scenario, it is essential to be able to restore the database from a backup to a state before the application error was introduced.

This section goes into details of backing up data and restoring it from YugabyteDB.

<div class="row">
  <div class="col-12 col-md-6 col-lg-12 col-xl-6">
    <a class="section-link icon-offset" href="back-up-data/">
      <div class="head">
        <img class="icon" src="/images/section_icons/manage/backup.png" aria-hidden="true" />
        <div class="title">Back up data</div>
      </div>
      <div class="body">
        This section describes how to create a backup of the data in YugabyteDB.
      </div>
    </a>
  </div>
  <div class="col-12 col-md-6 col-lg-12 col-xl-6">
    <a class="section-link icon-offset" href="restore-data/">
      <div class="head">
        <img class="icon" src="/images/section_icons/manage/enterprise/create_universe.png" aria-hidden="true" />
        <div class="title">Restore data</div>
      </div>
      <div class="body">
        This section describes how to restore data into YugabyteDB from a backup.
      </div>
    </a>
  </div>
</div>
<div class="row">
  <div class="col-12 col-md-6 col-lg-12 col-xl-6">
    <a class="section-link icon-offset" href="snapshot-ysql">
      <div class="head">
        <img class="icon" src="/images/section_icons/manage/backup.png" aria-hidden="true" />
        <div class="title">Distributed snapshot and restore data</div>
      </div>
      <div class="body">
        This section describes how to back up and restore data using distributed snapshots.
      </div>
    </a>
  </div>
  <div class="col-12 col-md-6 col-lg-12 col-xl-6">
    <a class="section-link icon-offset" href="point-in-time-recovery">
      <div class="head">
        <img class="icon" src="/images/section_icons/manage/enterprise/create_universe.png" aria-hidden="true" />
        <div class="title">Point-in-time recovery</div>
      </div>
      <div class="body">
        This section describes how to restore data from a particular point in time.
      </div>
    </a>
  </div>
</div>
