package app.codeg.android.agentconsole.permission

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.core.designsystem.component.CodeBlock
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.PermissionOption

/**
 * Agent Console overlay — permission card: digest-first, raw-command-folded.
 *
 * Target shape (user-designed, docs/CODEG_OVERLAY_PERMISSION_CARD_REQUIREMENT.md §3):
 *   ⚠️ 请求执行命令
 *   运行隔离 Gradle 单元测试
 *   目的：验证项目是否依赖全局 Gradle 配置
 *   影响：• ...  • ...
 *   [查看完整命令]
 *   [拒绝] [允许一次]
 *
 * The raw command is NEVER shown by default and NEVER determines card height;
 * expanded raw content is height-capped and scrollable. UNKNOWN digests are
 * shown as "需要审查" — risk is never faked as low.
 *
 * Modification for Agent Console overlay by ldohgfsdu (2026).
 */
@Composable
fun PermissionDigestCard(
    digest: PermissionDigest,
    options: List<PermissionOption>,
    onRespond: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    var submitting by remember(digest.rawCommand) { mutableStateOf<String?>(null) }
    var showRaw by remember(digest.rawCommand) { mutableStateOf(false) }
    val accent = colors.accent

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.bgElevated.copy(alpha = 0.96f))
            .border(0.5.dp, colors.surfaceStroke, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Header: ⚠️ + "请求执行命令" style title + risk badge.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Rounded.GppMaybe,
                contentDescription = null,
                tint = riskColor(digest.riskLevel, colors.danger, accent),
                modifier = Modifier.size(20.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(titleFor(digest), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                Text(
                    digest.summary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                )
            }
            RiskBadge(digest.riskLevel)
        }

        // Purpose — why the agent wants to do this.
        digest.purpose?.let { purpose ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("目的", fontSize = 11.sp, color = colors.textTertiary)
                Text(purpose, fontSize = 13.sp, color = colors.textSecondary)
            }
        }

        // Impact boundary list.
        if (digest.impacts.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("影响", fontSize = 11.sp, color = colors.textTertiary)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (impact in digest.impacts) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("•", color = colors.textTertiary, fontSize = 13.sp)
                            Text(impact, fontSize = 13.sp, color = colors.textPrimary, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Raw command — folded by default. Expanding shows the verbatim command,
        // height-capped and vertically scrollable; never breaks the screen.
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { showRaw = !showRaw }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                if (showRaw) "收起完整命令" else "查看完整命令",
                fontSize = 12.sp,
                color = colors.accent,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "(${digest.rawCommand.length} 字符)",
                fontSize = 11.sp,
                color = colors.textTertiary,
            )
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(16.dp),
            )
        }
        if (showRaw) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                CodeBlock(code = digest.rawCommand, language = "bash", collapseAfter = 12)
            }
        }

        // Action buttons — server-authoritative options (拒绝 / 允许一次 / ...).
        for (option in options) {
            OptionButton(
                option = option,
                submitting = submitting == option.optionId,
                enabled = submitting == null,
                onClick = {
                    submitting = option.optionId
                    onRespond(option.optionId)
                },
            )
        }
    }
}

@Composable
private fun RiskBadge(level: RiskLevel) {
    val colors = CodegTheme.colors
    val (label, color) = when (level) {
        RiskLevel.LOW -> "低风险" to Color(0xFF4CAF50)
        RiskLevel.MEDIUM -> "中风险" to Color(0xFFFFB300)
        RiskLevel.HIGH -> "高风险" to colors.danger
        RiskLevel.BLOCKED -> "危险操作" to colors.danger
        RiskLevel.UNKNOWN -> "需要审查" to colors.textTertiary
    }
    Text(
        label,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun titleFor(digest: PermissionDigest): String = when (digest.commandClass) {
    CommandClass.READ_ONLY -> "请求查看内容"
    CommandClass.FILE_WRITE -> "请求修改文件"
    CommandClass.FILE_DELETE -> "请求删除文件"
    CommandClass.NETWORK -> "请求网络访问"
    CommandClass.PACKAGE_INSTALL -> "请求安装软件包"
    CommandClass.GIT -> when (digest.riskLevel) {
        RiskLevel.HIGH, RiskLevel.BLOCKED -> "请求破坏性 Git 操作"
        else -> "请求 Git 操作"
    }
    CommandClass.BUILD -> "请求构建"
    CommandClass.TEST -> "请求运行测试"
    CommandClass.REPRODUCIBILITY_TEST -> "请求运行隔离构建测试"
    CommandClass.UNKNOWN -> "请求执行命令（需要审查）"
    CommandClass.SHELL -> "请求执行命令"
}

@Composable
private fun riskColor(level: RiskLevel, danger: Color, accent: Color): Color = when (level) {
    RiskLevel.LOW -> Color(0xFF4CAF50)
    RiskLevel.MEDIUM -> Color(0xFFFFB300)
    RiskLevel.HIGH, RiskLevel.BLOCKED -> danger
    RiskLevel.UNKNOWN -> Color(0xFF9E9E9E)
}

@Composable
private fun OptionButton(option: PermissionOption, submitting: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    val isReject = option.isReject
    val shape = RoundedCornerShape(10.dp)
    if (isReject) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            border = BorderStroke(0.5.dp, colors.surfaceStroke),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textPrimary),
        ) {
            if (submitting) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.textPrimary)
            } else {
                Text(option.name, fontWeight = FontWeight.SemiBold)
            }
        }
    } else {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = colors.onAccent,
                disabledContainerColor = colors.accent.copy(alpha = 0.4f),
                disabledContentColor = colors.onAccent.copy(alpha = 0.8f),
            ),
        ) {
            if (submitting) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.onAccent)
            } else {
                Text(option.name, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
