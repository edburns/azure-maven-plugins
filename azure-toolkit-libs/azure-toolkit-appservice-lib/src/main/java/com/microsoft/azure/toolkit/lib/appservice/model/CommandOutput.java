/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.appservice.model;

import lombok.Data;

@Data
public class CommandOutput {
    private String Output;
    private String Error;
    private int ExitCode;
}
