package org.ovirt.idea.ui

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object OvirtIcons {
    @JvmField
    val Command: Icon = IconLoader.getIcon("/icons/ovirt-command.svg", OvirtIcons::class.java)

    @JvmField
    val Usage: Icon = IconLoader.getIcon("/icons/ovirt-usage.svg", OvirtIcons::class.java)
}
