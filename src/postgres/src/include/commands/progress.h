/*-------------------------------------------------------------------------
 *
 * progress.h
 *	  Constants used with the progress reporting facilities defined in
 *	  pgstat.h.  These are possibly interesting to extensions, so we
 *	  expose them via this header file.  Note that if you update these
 *	  constants, you probably also need to update the views based on them
 *	  in system_views.sql.
 *
 * Portions Copyright (c) 1996-2018, PostgreSQL Global Development Group
 * Portions Copyright (c) 1994, Regents of the University of California
 *
 * src/include/commands/progress.h
 *
 *-------------------------------------------------------------------------
 */
#ifndef PROGRESS_H
#define PROGRESS_H

/* Progress parameters for (lazy) vacuum */
#define PROGRESS_VACUUM_PHASE					0
#define PROGRESS_VACUUM_TOTAL_HEAP_BLKS			1
#define PROGRESS_VACUUM_HEAP_BLKS_SCANNED		2
#define PROGRESS_VACUUM_HEAP_BLKS_VACUUMED		3
#define PROGRESS_VACUUM_NUM_INDEX_VACUUMS		4
#define PROGRESS_VACUUM_MAX_DEAD_TUPLES			5
#define PROGRESS_VACUUM_NUM_DEAD_TUPLES			6

/* Phases of vacuum (as advertised via PROGRESS_VACUUM_PHASE) */
#define PROGRESS_VACUUM_PHASE_SCAN_HEAP			1
#define PROGRESS_VACUUM_PHASE_VACUUM_INDEX		2
#define PROGRESS_VACUUM_PHASE_VACUUM_HEAP		3
#define PROGRESS_VACUUM_PHASE_INDEX_CLEANUP		4
#define PROGRESS_VACUUM_PHASE_TRUNCATE			5
#define PROGRESS_VACUUM_PHASE_FINAL_CLEANUP		6

/* Commands of PROGRESS_COPY */
#define PROGRESS_COPY_BYTES_PROCESSED 0
#define PROGRESS_COPY_BYTES_TOTAL 1
#define PROGRESS_COPY_TUPLES_PROCESSED 2
#define PROGRESS_COPY_TUPLES_EXCLUDED 3
#define PROGRESS_COPY_COMMAND 4
#define PROGRESS_COPY_TYPE 5
/* See progress_type below */
#define PROGRESS_COPY_STATUS 6

enum progress_type
{
    CP_IN_PROG,
    CP_ERROR,
    CP_SUCCESS
};

/* Commands of COPY (as advertised via PROGRESS_COPY_COMMAND) */
#define PROGRESS_COPY_COMMAND_FROM 1
#define PROGRESS_COPY_COMMAND_TO 2

/* Types of COPY commands (as advertised via PROGRESS_COPY_TYPE) */
#define PROGRESS_COPY_TYPE_FILE 1
#define PROGRESS_COPY_TYPE_PROGRAM 2
#define PROGRESS_COPY_TYPE_PIPE 3
#define PROGRESS_COPY_TYPE_CALLBACK 4

#endif
