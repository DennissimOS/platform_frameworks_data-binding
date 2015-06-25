/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.databinding.tool.processing;

import android.databinding.tool.processing.scopes.FileScopeProvider;
import android.databinding.tool.processing.scopes.LocationScopeProvider;
import android.databinding.tool.processing.scopes.ScopeProvider;
import android.databinding.tool.store.Location;
import android.databinding.tool.util.Preconditions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Utility class to keep track of "logical" stack traces, which we can use to print better error
 * reports.
 */
public class Scope {

    private static ThreadLocal<ScopeEntry> sScopeItems = new ThreadLocal<ScopeEntry>();
    static List<ScopedException> sDeferredExceptions = new ArrayList<>();

    public static void enter(ScopeProvider scopeProvider) {
        ScopeEntry peek = sScopeItems.get();
        ScopeEntry entry = new ScopeEntry(scopeProvider, peek);
        sScopeItems.set(entry);
    }

    public static void exit() {
        ScopeEntry entry = sScopeItems.get();
        Preconditions.checkNotNull(entry, "Inconsistent scope exit");
        sScopeItems.set(entry.mParent);
    }

    public static void defer(ScopedException exception) {
        sDeferredExceptions.add(exception);
    }

    public static void assertNoError() {
        if (sDeferredExceptions.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (ScopedException ex : sDeferredExceptions) {
            sb.append(ex.getMessage()).append("\n");
        }
        throw new RuntimeException("Found data binding errors.\n" + sb.toString());
    }

    static String produceScopeLog() {
        StringBuilder sb = new StringBuilder();
        sb.append("full scope log\n");
        ScopeEntry top = sScopeItems.get();
        while (top != null) {
            ScopeProvider provider = top.mProvider;
            sb.append("---").append(provider).append("\n");
            if (provider instanceof FileScopeProvider) {
                sb.append("file:").append(((FileScopeProvider) provider).provideScopeFilePath())
                        .append("\n");
            }
            if (provider instanceof LocationScopeProvider) {
                LocationScopeProvider loc = (LocationScopeProvider) provider;
                sb.append("loc:");
                List<Location> locations = loc.provideScopeLocation();
                if (locations == null) {
                    sb.append("null\n");
                } else {
                    for (Location location : locations) {
                        sb.append(location).append("\n");
                    }
                }
            }
            top = top.mParent;
        }
        sb.append("---\n");
        return sb.toString();
    }

    static ScopedErrorReport createReport() {
        ScopeEntry top = sScopeItems.get();
        String filePath = null;
        List<Location> locations = null;
        while (top != null && (filePath == null || locations == null)) {
            ScopeProvider provider = top.mProvider;
            if (locations == null && provider instanceof LocationScopeProvider) {
                locations = findAbsoluteLocationFrom(top, (LocationScopeProvider) provider);
            }
            if (filePath == null && provider instanceof FileScopeProvider) {
                filePath = ((FileScopeProvider) provider).provideScopeFilePath();
            }
            top = top.mParent;
        }
        return new ScopedErrorReport(filePath, locations);
    }

    private static List<Location> findAbsoluteLocationFrom(ScopeEntry entry,
            LocationScopeProvider top) {
        List<Location> locations = top.provideScopeLocation();
        if (locations == null || locations.isEmpty()) {
            return null;
        }
        if (locations.size() == 1) {
            return Arrays.asList(locations.get(0).toAbsoluteLocation());
        }
        // We have more than 1 location. Depending on the scope, we may or may not want all of them
        List<Location> chosen = new ArrayList<>();
        for (Location location : locations) {
            Location absLocation = location.toAbsoluteLocation();
            if (validatedContained(entry.mParent, absLocation)) {
                chosen.add(absLocation);
            }
        }
        return chosen.isEmpty() ? locations : chosen;
    }

    private static boolean validatedContained(ScopeEntry parent, Location absLocation) {
        if (parent == null) {
            return true;
        }
        ScopeProvider provider = parent.mProvider;
        if (!(provider instanceof LocationScopeProvider)) {
            return validatedContained(parent.mParent, absLocation);
        }
        List<Location> absoluteParents = findAbsoluteLocationFrom(parent,
                (LocationScopeProvider) provider);
        for (Location location : absoluteParents) {
            if (location.contains(absLocation)) {
                return true;
            }
        }
        return false;
    }

    private static class ScopeEntry {

        ScopeProvider mProvider;

        ScopeEntry mParent;

        public ScopeEntry(ScopeProvider scopeProvider, ScopeEntry parent) {
            mProvider = scopeProvider;
            mParent = parent;
        }
    }
}