// Copyright (c) 2013 Intel Corporation. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.xwalk.runtime.extension.api.contacts;

import android.content.ContentProviderOperation;
import android.content.ContentProviderOperation.Builder;
import android.content.ContentResolver;
import android.content.OperationApplicationException;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Event;
import android.provider.ContactsContract.CommonDataKinds.GroupMembership;
import android.provider.ContactsContract.CommonDataKinds.Im;
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.Data;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.xwalk.runtime.extension.api.contacts.ContactConstants.ContactMap;

/**
 * This class saves contacts according to a given JSONString.
 */
public class ContactSaver {
    private ContactUtils mUtils;
    private final String mTag;

    private JSONObject mContact;
    private ContactJson mJson;
    private String mId;
    private boolean mIsUpdate;
    private ArrayList<ContentProviderOperation> mOps;

    public ContactSaver(ContentResolver resolver, String tag) {
        mUtils = new ContactUtils(resolver, tag);
        mTag = tag;
    }

    // Update a contact
    private Builder newUpdateBuilder(String mimeType) {
        Builder builder = ContentProviderOperation.newUpdate(Data.CONTENT_URI);
        builder.withSelection(
                Data.CONTACT_ID + "=? AND " + Data.MIMETYPE + "=?",
                new String[]{mId, mimeType});
        return builder;
    }

    // Add a new contact
    private Builder newInsertBuilder(String mimeType) {
        Builder builder = ContentProviderOperation.newInsert(Data.CONTENT_URI);
        builder.withValueBackReference(Data.RAW_CONTACT_ID, 0);
        builder.withValue(Data.MIMETYPE, mimeType);
        return builder;
    }

    // Add a new field to an existing contact
    private Builder newInsertFieldBuilder(String mimeType) {
        Builder builder = ContentProviderOperation.newInsert(Data.CONTENT_URI);
        builder.withValue(Data.RAW_CONTACT_ID, mUtils.getRawId(mId));
        builder.withValue(Data.MIMETYPE, mimeType);
        return builder;
    }

    // Add a new contact or add a new field to an existing contact
    private Builder newInsertContactOrFieldBuilder(String mimeType) {
        return mIsUpdate ? newInsertFieldBuilder(mimeType)
                         : newInsertBuilder(mimeType);
    }

    // Add a new contact or update a contact
    private Builder newBuilder(String mimeType) {
        return mIsUpdate ? newUpdateBuilder(mimeType)
                         : newInsertBuilder(mimeType);
    }

    // Build by a data array with types
    private void buildByArray(ContactMap contactMap) {
        if (!mContact.has(contactMap.mName)) {
            return;
        }

        // When updating multiple records of one MIMEType,
        // we need to flush the old records and then insert new ones later.
        //
        // For example, it is possible that a contact has several phone numbers,
        // in data table it will be like this:
        // CONTACT_ID  MIMETYPE  TYPE  DATA1
        // ------------------------------------------
        //        374  Phone_v2  Work  +4412345678
        //        374  Phone_v2  Work  +4402778877
        // In this case if we update by SQL selection clause directly,
        // will get two same records of last update value.
        //
        if (mIsUpdate) {
            mUtils.cleanByMimeType(mId, contactMap.mMimeType);
        }
        try {
            final JSONArray fields = mContact.getJSONArray(contactMap.mName);
            for (int i = 0; i < fields.length(); ++i) {
                ContactJson mJson = new ContactJson(fields.getJSONObject(i));
                List<String> typeList = mJson.getStringArray("types");
                boolean bPref = mJson.getBoolean("preferred");
                if (!typeList.isEmpty()) {
                    // Currently we can't store multiple types in Android
                    final String type = typeList.get(0);
                    final Integer iType = contactMap.mTypeValueMap.get(type);

                    Builder builder = newInsertContactOrFieldBuilder(contactMap.mMimeType);
                    if (bPref) {
                        builder.withValue(contactMap.mTypeMap.get("isPrimary"), 1);
                        builder.withValue(contactMap.mTypeMap.get("isSuperPrimary"), 1);
                    }
                    if (iType != null) {
                        builder.withValue(contactMap.mTypeMap.get("type"), iType);
                    }
                    for (Map.Entry<String, String> entry : contactMap.mDataMap.entrySet()) {
                        String value = mJson.getString(entry.getValue());
                        if (contactMap.mName.equals("impp")) {
                            String imProtocol = value.substring(0, value.indexOf(':'));
                            value = value.substring(value.indexOf(':')+1);
                            builder.withValue(Im.PROTOCOL,
                                    ContactConstants.imProtocolMap.get(imProtocol));
                        }
                        builder.withValue(entry.getKey(), value);
                    }
                    mOps.add(builder.build());
                }
            }
        } catch (JSONException e) {
            Log.e(mTag, "Failed to parse json data of " + contactMap.mName + ": " + e);
        }
    }

    // Build by a data array without types
    private void buildByArray(String mimeType, String data, List<String> dataEntries) {
        if (mIsUpdate) {
            mUtils.cleanByMimeType(mId, mimeType);
        }
        for (String entry : dataEntries) {
            Builder builder = newInsertContactOrFieldBuilder(mimeType);
            builder.withValue(data, entry);
            mOps.add(builder.build());
        }
    }

    private void buildByArray(ContactMap contactMap, String data, List<String> dataEntries) {
        if (mContact.has(contactMap.mName)) {
            buildByArray(contactMap.mMimeType, data, dataEntries);
        }
    }

    private void buildByDate(String name, String mimeType, String data) {
        buildByDate(name, mimeType, data, null, 0);
    }

    private void buildByDate(String name, String mimeType, String data, String type, int dateType) {
        if (!mContact.has(name)) {
            return;
        }

        final String dateString = mJson.getString(name);
        final String dateData = mUtils.dateFormat(dateString);
        Builder builder = newBuilder(mimeType);
        builder.withValue(data, dateData);
        if (type != null) {
            builder.withValue(type, dateType);
        }
        mOps.add(builder.build());
    }

    private void buildByEvent(String eventName, int eventType) {
        buildByDate(eventName, Event.CONTENT_ITEM_TYPE, Event.START_DATE, Event.TYPE, eventType);
    }

    private void buildByContactMapList() {
        for (ContactMap contactMap : ContactConstants.contactMapList) {
            if (contactMap.mTypeMap != null) { // Field that has type.
                buildByArray(contactMap);
            } else { // Field that contains no type.
                buildByArray(contactMap, contactMap.mDataMap.get("data"),
                        mJson.getStringArray(contactMap.mName));
            }
        }
    }

    public JSONObject save(String saveString) {
        mOps = new ArrayList<ContentProviderOperation>();
        try {
            mContact = new JSONObject(saveString);
        } catch (JSONException e) {
            Log.e(mTag, "Failed to parse json data: " + e);
            return new JSONObject();
        }

        mJson = new ContactJson(mContact);

        Builder builder = null;
        mId = mJson.getString("id");
        mIsUpdate = mUtils.hasID(mId);

        Set<String> oldRawIds = null;
        if (!mIsUpdate) { // Create a null record for inserting later
            oldRawIds = mUtils.getCurrentRawIds();
            mId = null;
            builder = ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI);
            builder.withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null);
            builder.withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null);
            mOps.add(builder.build());
        }

        // W3C                  Android
        //-------------------------------------------------
        // displayName          StructuredName.display_name
        // honorificPrefixes    StructuredName.prefix
        // givenNames           StructuredName.given_name
        // additionalNames      StructuredName.middle_name
        // familyNames          StructuredName.family_name
        // honorificSuffixes    StructuredName.suffix
        // nicknames            Nickname.name
        if (mContact.has("name")) {
            final JSONObject name = mJson.getObject("name");
            final ContactJson nameJson = new ContactJson(name);
            builder = newBuilder(StructuredName.CONTENT_ITEM_TYPE);
            builder.withValue(StructuredName.DISPLAY_NAME, nameJson.getString("displayName"));
            //FIXME(hdq): should read all names
            builder.withValue(StructuredName.FAMILY_NAME, nameJson.getArrayTop("familyNames"));
            builder.withValue(StructuredName.GIVEN_NAME, nameJson.getArrayTop("givenNames"));
            builder.withValue(StructuredName.MIDDLE_NAME, nameJson.getArrayTop("additionalNames"));
            builder.withValue(StructuredName.PREFIX, nameJson.getArrayTop("honorificPrefixes"));
            builder.withValue(StructuredName.SUFFIX, nameJson.getArrayTop("honorificSuffixes"));
            mOps.add(builder.build());
            if (name.has("nicknames")) {
                builder = newBuilder(Nickname.CONTENT_ITEM_TYPE);
                builder.withValue(Nickname.NAME, nameJson.getArrayTop("nicknames"));
                mOps.add(builder.build());
            }
        }

        if (mContact.has("categories")) {
            List<String> groupIds = new ArrayList<String>();
            for (String groupTitle : mJson.getStringArray("categories")) {
                groupIds.add(mUtils.getEnsuredGroupId(groupTitle));
            }
            buildByArray(GroupMembership.CONTENT_ITEM_TYPE, GroupMembership.GROUP_ROW_ID, groupIds);
        }

        if (mContact.has("gender")) {
            final String gender = mJson.getString("gender");
            if (Arrays.asList("male", "female", "other", "none", "unknown").contains(gender)) {
                builder = newBuilder(ContactConstants.CUSTOM_MIMETYPE_GENDER);
                builder.withValue(Data.DATA1, gender);
                mOps.add(builder.build());
            }
        }

        buildByDate("lastUpdated", ContactConstants.CUSTOM_MIMETYPE_LASTUPDATED, Data.DATA1);
        buildByEvent("birthday", Event.TYPE_BIRTHDAY);
        buildByEvent("anniversary", Event.TYPE_ANNIVERSARY);

        buildByContactMapList();

        // Perform the operation batch
        try {
            mUtils.mResolver.applyBatch(ContactsContract.AUTHORITY, mOps);
        } catch (RemoteException e) {
            Log.e(mTag, "Failed to apply batch: " + e);
        } catch (OperationApplicationException e) {
            Log.e(mTag, "Failed to apply batch: " + e);
        }

        // If it is a new contact, need to get and return its auto-generated id.
        if (!mIsUpdate) {
            Set<String> newRawIds = mUtils.getCurrentRawIds();
            newRawIds.removeAll(oldRawIds);
            if (newRawIds.size() != 1) {
                Log.e(mTag, "build() - Something wrong after batch applied,"
                        + "new raw ids are: " + newRawIds.toString());
                return mContact;
            }
            String id = mUtils.getId(newRawIds.iterator().next());
            try {
                mContact.put("id", id);
            } catch (JSONException e) {
                Log.e(mTag, "build() - Failed to put id " + id + " into contact");
            }
        }
        return mContact;
    }
}
