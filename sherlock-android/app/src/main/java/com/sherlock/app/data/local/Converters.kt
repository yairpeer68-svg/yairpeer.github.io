package com.sherlock.app.data.local

import androidx.room.TypeConverter
import com.sherlock.app.data.model.ErrorType
import com.sherlock.app.data.model.Priority
import com.sherlock.app.data.model.SearchType
import com.sherlock.app.data.model.SiteCategory

class Converters {
    @TypeConverter fun fromSiteCategory(v: SiteCategory): String = v.name
    @TypeConverter fun toSiteCategory(v: String): SiteCategory = SiteCategory.valueOf(v)
    @TypeConverter fun fromSearchType(v: SearchType): String = v.name
    @TypeConverter fun toSearchType(v: String): SearchType = SearchType.valueOf(v)
    @TypeConverter fun fromPriority(v: Priority): String = v.name
    @TypeConverter fun toPriority(v: String): Priority = Priority.valueOf(v)
    @TypeConverter fun fromErrorType(v: ErrorType): String = v.name
    @TypeConverter fun toErrorType(v: String): ErrorType = ErrorType.valueOf(v)
}
