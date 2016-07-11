/* This file is part of VoltDB.
 * Copyright (C) 2008-2016 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb;

import org.voltcore.logging.VoltLogger;

public class VoltTrace {
    private static final VoltLogger log = new VoltLogger("HOST");

    public static void bDuration(String filename, String name, String category)
    {
        begin(filename, 'B', name, category, -1, null);
    }

    public static void bDuration(String filename, String name, String category, String args)
    {
        begin(filename, 'B', name, category, -1, args);
    }

    public static void bAsync(String filename, String name, String category, long id)
    {
        begin(filename, 'b', name, category, id, null);
    }

    public static void bAsync(String filename, String name, String category, long id, String args)
    {
        begin(filename, 'b', name, category, id, args);
    }

    public static void nAsync(String filename, String name, String category, long id)
    {
        begin(filename, 'n', name, category, id, null);
    }

    public static void nAsync(String filename, String name, String category, long id, String args)
    {
        begin(filename, 'n', name, category, id, args);
    }

    private static void begin(String filename, char type, String name, String category, long id, String args)
    {
        log.info(String.format("TRACING begin fn %s %s name %s cat %s id %d args %s",
                               filename, type, name, category, id, args));
    }

    public static void eDuration(String filename)
    {
        end(filename, 'E', null, null, -1);
    }

    public static void eAsync(String filename, String name, String category, long id)
    {
        end(filename, 'E', name, category, id);
    }

    private static void end(String filename, char type, String name, String category, long id)
    {
        log.info(String.format("TRACING end fn %s %s name %s cat %s id %d",
                               filename, type, name, category, id));
    }

    public static void meta(String filename, String name, String args)
    {
        log.info(String.format("TRACING meta fn %s name %s args %s",
                               filename, name, args));
    }

    public static void close(String filename)
    {
        log.info(String.format("TRACING close fn %s", filename));
    }
}
