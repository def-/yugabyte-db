---
title: Built-in functions and operators [YSQL]
headerTitle: Built-in functions and operators
linkTitle: Built-in functions and operators
description: YSQL supports all PostgreSQL-compatible built-in functions and operators.
image: /images/section_icons/api/ysql.png
menu:
  preview:
    identifier: api-ysql-exprs
    parent: api-ysql
    weight: 200
aliases:
  - /preview/api/ysql/exprs/
type: indexpage
---

YSQL supports all PostgreSQL-compatible built-in functions and operators. The following are the currently documented ones.

| Statement | Description |
|-----------|-------------|
| [nextval()](func_nextval) | Returns the next value for the specified sequence in the current session |
| [currval()](func_currval) | Returns the value returned by the most recent call to _nextval()_ for the specified sequence in the current session |
| [lastval()](func_lastval) | Returns the value returned by the most recent call to _nextval()_ for _any_ sequence in the current session |
| [yb_hash_code()](func_yb_hash_code) | Returns the partition hash code for a given set of expressions |
| [yb_is_local_table()](func_yb_is_local_table) | Returns whether the given 'oid' is a table replicated only in the local region |
| [JSON functions and operators](../datatypes/type_json/functions-operators/) | Detailed list of JSON-specific functions and operators |
| [Array functions and operators](../datatypes/type_array/functions-operators/) | Detailed list of array-specific functions and operators |
| [Aggregate functions](./aggregate_functions/) | Detailed list of YSQL aggregate functions |
| [Window functions](./window_functions/) | Detailed list of YSQL window functions |
| [Date-time operators](../datatypes/type_datetime/operators/) | List of operators for the date and time data types |
| [General-purpose date-functions](../datatypes/type_datetime/functions/) | List of general purpose functions for the date and time data types |
| [Date-time formatting functions](../datatypes/type_datetime/formatting-functions/) | List of formatting functions for the date and time data types |
