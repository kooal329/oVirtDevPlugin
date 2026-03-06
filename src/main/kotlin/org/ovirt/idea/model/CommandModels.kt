package org.ovirt.idea.model

data class CommandInfo(
    val name: String,
    val qualifiedName: String,
    val filePath: String,
    val parametersClass: String?,
    val calledCommands: Set<String>,
    val usages: Set<UsageLocation>,
    val isVdsCommand: Boolean = false
)

data class UsageLocation(
    val filePath: String,
    val line: Int,
    val preview: String
)
