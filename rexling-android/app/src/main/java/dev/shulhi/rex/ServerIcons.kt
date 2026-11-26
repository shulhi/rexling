package dev.shulhi.rex

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Small stroke icons stay sharp at header and picker sizes. */
@Composable internal fun ServerGlyph(icon: ServerIcon,modifier: Modifier=Modifier,color: Color=MaterialTheme.colorScheme.onSurfaceVariant) {
    Canvas(modifier.size(20.dp)) {
        val stroke=Stroke(1.6f)
        withTransform({scale(size.width/24f,size.height/24f,pivot=Offset.Zero)}) {
            fun line(x: Float,y: Float,x2: Float,y2: Float)=drawLine(color,Offset(x,y),Offset(x2,y2),1.6f)
            fun box(x: Float,y: Float,w: Float,h: Float,r: Float=1.5f)=drawRoundRect(color,Offset(x,y),Size(w,h),CornerRadius(r),style=stroke)
            when(icon) {
                ServerIcon.GLOBE->{
                    drawCircle(color,9f,Offset(12f,12f),style=stroke)
                    drawOval(color,Offset(8f,3f),Size(8f,18f),style=stroke)
                    line(3f,12f,21f,12f);line(5f,7f,19f,7f);line(5f,17f,19f,17f)
                }
                ServerIcon.SERVER->{
                    box(3f,4f,18f,7f);box(3f,13f,18f,7f)
                    for(y in listOf(7.5f,16.5f)) {drawCircle(color,1f,Offset(6.5f,y));line(11f,y,18f,y)}
                }
                ServerIcon.CLOUD->{
                    val path=Path().apply {
                        moveTo(6f,19f);cubicTo(0f,19f,0f,11f,6f,11f)
                        cubicTo(5f,3f,17f,2f,18f,10f)
                        cubicTo(25f,10f,25f,19f,18f,19f);close()
                    };drawPath(path,color,style=stroke)
                }
                ServerIcon.MACBOOK->{
                    box(5f,4f,14f,12f,1f)
                    val path=Path().apply {moveTo(4f,17f);lineTo(20f,17f);lineTo(22f,20f);lineTo(2f,20f);close()}
                    drawPath(path,color,style=stroke)
                }
                ServerIcon.MAC_MINI->{box(3f,8f,18f,8f,2f);line(6f,12f,8f,12f);drawCircle(color,.8f,Offset(18f,12f))}
                ServerIcon.MAC_STUDIO->{box(4f,5f,16f,14f,2f);line(4f,14f,20f,14f);line(7f,16.5f,10f,16.5f);drawCircle(color,.8f,Offset(17f,16.5f))}
                ServerIcon.DESKTOP->{box(3f,3f,18f,13f,1f);line(12f,16f,12f,21f);line(8f,21f,16f,21f)}
            }
        }
    }
}

@Composable internal fun ServerIconPicker(selected: ServerIcon,compact: Boolean=false,enabled: Boolean=true,onSelect: (ServerIcon)->Unit) {
    var open by remember {mutableStateOf(false)}
    Box(Modifier.then(if(compact) Modifier.padding(top=8.dp) else Modifier)) {
        Row((if(compact) Modifier.height(56.dp).width(62.dp).clip(RoundedCornerShape(10.dp))
            .border(1.dp,MaterialTheme.colorScheme.outline,RoundedCornerShape(10.dp)) else Modifier.fillMaxWidth())
            .clickable(enabled=enabled,role=Role.Button) {open=true}.semantics {contentDescription="Choose server icon";stateDescription=selected.label}
            .padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=if(compact) Arrangement.Center else Arrangement.spacedBy(10.dp)) {
            ServerGlyph(selected,color=MaterialTheme.colorScheme.onSurface)
            if(!compact) {
                Text("Icon",Modifier.weight(1f),style=SettingsType.row)
                Text(selected.label,style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
            } else Spacer(Modifier.width(4.dp))
            ChromeSymbol(ChromeIcon.CHEVRON,Modifier.size(14.dp),MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(compact) Text("Icon",Modifier.offset(x=9.dp,y=(-8).dp).background(MaterialTheme.colorScheme.surface)
            .padding(horizontal=4.dp),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
        DropdownMenu(open,{open=false},containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp) {
            ServerIcon.entries.forEach {icon->
                DropdownMenuItem(text={Text(icon.label,style=SettingsType.row)},
                    leadingIcon={ServerGlyph(icon)},trailingIcon={if(selected==icon) Text("✓",color=MaterialTheme.colorScheme.primary)},
                    onClick={onSelect(icon);open=false})
            }
        }
    }
}
