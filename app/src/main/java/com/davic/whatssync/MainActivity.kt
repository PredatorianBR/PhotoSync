package com.davic.whatssync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest

class MainActivity : ComponentActivity() {

    private lateinit var syncManager: SyncManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syncManager = SyncManager.getInstance(applicationContext)

        setContent {
            WhatsSyncTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WhatsSyncApp(syncManager = syncManager)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Reload contacts and refresh accessibility status on resume
        syncManager.loadContacts()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WhatsSyncApp(syncManager: SyncManager) {
    val context = LocalContext.current
    
    val contacts by syncManager.contacts.collectAsState()
    val syncState by syncManager.syncState.collectAsState()
    
    var hasContactsPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasContactsPermission = permissions[Manifest.permission.READ_CONTACTS] == true &&
                permissions[Manifest.permission.WRITE_CONTACTS] == true
        if (hasContactsPermission) {
            syncManager.loadContacts()
        } else {
            Toast.makeText(context, "Permissão de contatos é obrigatória", Toast.LENGTH_LONG).show()
        }
    }

    var isAccessibilityActive by remember { mutableStateOf(syncManager.isAccessibilityServiceConnected()) }
    
    // Periodically verify accessibility service connection
    LaunchedEffect(key1 = syncState) {
        isAccessibilityActive = syncManager.isAccessibilityServiceConnected()
    }

    // Trigger loading contacts when permissions are granted
    LaunchedEffect(key1 = hasContactsPermission) {
        if (hasContactsPermission) {
            syncManager.loadContacts()
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    val selectedContactIds = remember { mutableStateListOf<Long>() }

    val filteredContacts = remember(contacts, searchQuery) {
        contacts.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.phoneNumber.contains(searchQuery)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = "WhatsSync",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            if (hasContactsPermission && isAccessibilityActive) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (syncState is SyncState.Syncing) {
                            syncManager.stopSync()
                        } else {
                            val selectedList = contacts.filter { selectedContactIds.contains(it.contactId) }
                            if (selectedList.isEmpty()) {
                                Toast.makeText(context, "Selecione pelo menos um contato", Toast.LENGTH_SHORT).show()
                            } else {
                                syncManager.startSync(selectedList)
                            }
                        }
                    },
                    containerColor = if (syncState is SyncState.Syncing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    icon = {
                        Icon(
                            imageVector = if (syncState is SyncState.Syncing) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                    },
                    text = {
                        Text(
                            text = if (syncState is SyncState.Syncing) "Parar" else "Sincronizar (${selectedContactIds.size})"
                        )
                    }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Permission & Accessibility Check Cards
            if (!hasContactsPermission || !isAccessibilityActive) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!hasContactsPermission) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Permissão de Contatos Necessária",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "O WhatsSync precisa acessar e editar seus contatos para listar e atualizar as fotos.",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        permissionLauncher.launch(
                                            arrayOf(
                                                Manifest.permission.READ_CONTACTS,
                                                Manifest.permission.WRITE_CONTACTS
                                            )
                                        )
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Conceder Permissão", color = Color.White)
                                }
                            }
                        }
                    }

                    if (hasContactsPermission && !isAccessibilityActive) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Text(
                                        text = "Serviço de Acessibilidade Inativo",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Para capturar as fotos de perfil localmente, ative o serviço do WhatsSync nas configurações do Android.",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Passo a passo:\n1. Clique em 'Ativar Serviço' abaixo.\n2. Vá em 'Apps instalados' ou 'Serviços instalados'.\n3. Selecione 'WhatsSync Automador de Fotos' e ative-o.\n\n* Android 13+: Se aparecer 'Configuração restrita', vá em Configurações do Celular > Apps > WhatsSync > Clique nos 3 pontinhos no canto superior direito > Selecione 'Permitir configurações restritas' e repita o processo.",
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        try {
                                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Erro ao abrir configurações", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                ) {
                                    Text("Ativar Serviço")
                                }
                            }
                        }
                    }
                }
            }

            // Sync Status Dashboard (visible when syncing)
            AnimatedVisibility(
                visible = syncState is SyncState.Syncing || syncState is SyncState.Completed,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        when (val state = syncState) {
                            is SyncState.Syncing -> {
                                Text(
                                    text = "Sincronizando...",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Contato atual: ${state.contactName}",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                val progress = state.index.toFloat() / state.total.toFloat()
                                LinearProgressIndicator(
                                    progress = progress,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${state.index} de ${state.total} contatos processados",
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                                )
                            }
                            is SyncState.Completed -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = "Sincronização Concluída!",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        Text(
                                            text = "${state.successCount} fotos atualizadas com sucesso, ${state.failedCount} falhas.",
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = { syncManager.stopSync() },
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("Entendido")
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }

            if (hasContactsPermission) {
                // Search & Selection Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Buscar contatos...") },
                        leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    IconButton(
                        onClick = {
                            val availableContacts = filteredContacts.filter { it.whatsappRawContactId != null || it.whatsappDataId != null }
                            if (selectedContactIds.size == availableContacts.size) {
                                selectedContactIds.clear()
                            } else {
                                selectedContactIds.clear()
                                selectedContactIds.addAll(availableContacts.map { it.contactId })
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (selectedContactIds.size == filteredContacts.filter { it.whatsappRawContactId != null || it.whatsappDataId != null }.size) 
                                Icons.Default.SelectAll else Icons.Default.Checklist,
                            contentDescription = "Selecionar Todos"
                        )
                    }
                }

                // Contact List Label
                Text(
                    text = "Contatos (${filteredContacts.size})",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )

                // Contacts LazyColumn
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(
                        items = filteredContacts,
                        key = { it.contactId }
                    ) { contact ->
                        ContactItem(
                            contact = contact,
                            isSelected = selectedContactIds.contains(contact.contactId),
                            onSelectionChanged = { selected ->
                                if (selected) {
                                    selectedContactIds.add(contact.contactId)
                                } else {
                                    selectedContactIds.remove(contact.contactId)
                                }
                            }
                        )
                    }
                }
            } else {
                // Empty state when permissions are not granted
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Aguardando permissão de contatos...",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun ContactItem(
    contact: PhoneContact,
    isSelected: Boolean,
    onSelectionChanged: (Boolean) -> Unit
) {
    val isLinkedToWhatsapp = contact.whatsappRawContactId != null || contact.whatsappDataId != null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isLinkedToWhatsapp) { onSelectionChanged(!isSelected) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar Image
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            if (contact.photoUri != null) {
                AsyncImage(
                    model = contact.photoUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Contact info text
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = contact.name,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = if (isLinkedToWhatsapp) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
            Text(
                text = contact.phoneNumber,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Status Indicator Row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Whatsapp mapping status badge
            if (isLinkedToWhatsapp) {
                // Checkbox for selection
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onSelectionChanged(it) }
                )
            } else {
                // Show badge that contact has no WhatsApp linked
                Text(
                    text = "Sem WhatsApp",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            // Sync Status
            when (contact.syncStatus) {
                SyncStatus.SYNCING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                SyncStatus.SUCCESS -> {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Sucesso",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                SyncStatus.FAILED -> {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Falha",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
                else -> {} // PENDING - show nothing
            }
        }
    }
}
